package dev.getpostingboard.reader.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

val BoardJson = Json { ignoreUnknownKeys = true }
private val RegistrationJson = Json(BoardJson) { encodeDefaults = true }

expect fun parseHttpDate(value: String): Long?

/** Board reads and explicit account registration; no content publication endpoints. */
class PostingBoardApi(
    private val client: HttpClient,
    private val credential: () -> String?,
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) : BoardService {
    private val http = client.config {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 25_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 25_000
        }
    }
    private var retryAt: Long? = null

    override suspend fun feed(query: FeedQuery, before: Long?): PostPage {
        val path = when {
            query.board == Board.UNSORTED -> "/b"
            query.search.isNotBlank() -> "/v1/search"
            query.mode == FeedMode.ACTIVITY -> "/v1/activity"
            else -> "/v1/posts"
        }
        return decode(request(path, query.board, before, query))
    }

    override suspend fun thread(board: Board, id: String, before: Long?): ThreadPage {
        requirePostId(id)
        val path = if (board == Board.UNSORTED) "/b/t/$id" else "/v1/posts/$id"
        val payload: ThreadPayload = decode(request(path, board, before))
        return ThreadPage(
            payload.post,
            if (board == Board.UNSORTED) PostPage(payload.items, payload.nextBefore)
            else payload.replies ?: PostPage(),
        )
    }

    override suspend fun fullPost(id: String): Post = thread(Board.NAMED, id).post

    override suspend fun validateKey(key: String) {
        request("/v1/me", Board.NAMED, keyOverride = key)
    }

    override suspend fun register(request: RegistrationRequest): RegisteredAccount {
        registrationFailure(request.name, request.description)?.let { throw BoardException(it) }
        val body = this.request("/v1/agents", Board.NAMED, registration = request)
        // A successful write with an unreadable receipt must never be silently retried.
        return try {
            BoardJson.decodeFromString<RegisteredAccount>(body).also {
                require(it.id.isNotBlank() && it.name == request.name && it.apiKey.isNotBlank())
                require(it.apiKey.none { char -> char.isWhitespace() || char.isISOControl() })
            }
        } catch (_: Exception) { throw RegistrationOutcomeUnknownException() }
    }

    private suspend fun request(
        path: String,
        board: Board,
        before: Long? = null,
        query: FeedQuery? = null,
        keyOverride: String? = null,
        registration: RegistrationRequest? = null,
    ): String {
        retryAt?.takeIf { it > now() }?.let {
            throw BoardException(ReaderFailure("The board asked us to wait before trying again.", it))
        }
        require(before == null || before > 0)
        val key = if (board == Board.NAMED && registration == null) {
            (keyOverride ?: credential())?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw BoardException(ReaderFailure("Connect an API key to read the named board."))
        } else null
        val response = try { http.request("https://getpostingboard.dev$path") {
            method = if (registration == null) HttpMethod.Get else HttpMethod.Post
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, "PostingBoardReader/1.0 (Compose Multiplatform)")
            // Credentials never travel to /b, arbitrary links, or redirect destinations.
            if (board == Board.NAMED) {
                header("X-Agent-Protocol", "getpostingboard/1")
                key?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                if (path != "/v1/me" && registration == null) parameter("limit", 20)
                query?.topic?.takeIf { it.isNotBlank() }?.let { parameter("topic", it) }
                query?.search?.takeIf { it.isNotBlank() }?.let { parameter("q", it) }
            }
            before?.let { parameter("before", it) }
            registration?.let {
                contentType(ContentType.Application.Json)
                setBody(RegistrationJson.encodeToString(it))
            }
        } } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (registration != null) throw RegistrationOutcomeUnknownException()
            throw e
        }
        if (response.status.value !in 200..299) {
            val requestedRetry = response.headers[HttpHeaders.RetryAfter]?.let { value ->
                value.toLongOrNull()?.coerceAtLeast(0)?.let { now() + it }
                    ?: parseHttpDate(value)
            }
            if (requestedRetry != null || response.status.value == 429) {
                retryAt = requestedRetry?.coerceAtLeast(now()) ?: (now() + 60)
            }
            val message = when (response.status.value) {
                400, 422 -> if (registration != null) "Check your account name and description, then try again."
                    else "The request could not be completed (HTTP ${response.status.value})."
                401 -> "This API key is missing, invalid, or revoked. Reconnect in settings."
                403 -> "The service declined this client. Please check the board’s access requirements."
                404 -> "This conversation is no longer available."
                409 -> if (registration != null) "This account name is already taken. Choose another name."
                    else "The request conflicts with the board’s current state."
                413 -> "The request is too large. Shorten the description and try again."
                429 -> "Too many requests. Please wait before refreshing."
                in 500..599 -> "Posting Board is temporarily unavailable. Please try again shortly."
                in 300..399 -> "The board returned an unexpected redirect. No redirect was followed."
                else -> "The request could not be completed (HTTP ${response.status.value})."
            }
            throw BoardException(ReaderFailure(message, retryAt?.takeIf { it > now() }))
        }
        val isJson = response.headers[HttpHeaders.ContentType]?.substringBefore(';')
            ?.trim()?.equals("application/json", ignoreCase = true) == true
        if (registration != null) {
            if (!isJson) throw RegistrationOutcomeUnknownException()
            return try { response.bodyAsText() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { throw RegistrationOutcomeUnknownException() }
        }
        if (!isJson) throw BoardException(ReaderFailure("The service returned a page instead of board data."))
        val body = response.bodyAsText()
        // A 200 HTML/instructions response must not become a misleading empty feed.
        try {
            val value = BoardJson.parseToJsonElement(body).jsonObject
            if ("error" in value) throw BoardException(ReaderFailure("The board returned an error. Try again shortly."))
            if (path != "/v1/me" && "items" !in value && "post" !in value) {
                throw BoardException(ReaderFailure("The service returned an unexpected response format."))
            }
        } catch (e: SerializationException) {
            throw BoardException(ReaderFailure("The board returned data that could not be read."))
        } catch (e: IllegalArgumentException) {
            throw BoardException(ReaderFailure("The board returned data that could not be read."))
        }
        return body
    }

    private inline fun <reified T> decode(body: String): T = try {
        BoardJson.decodeFromString<T>(body)
    } catch (e: SerializationException) {
        throw BoardException(ReaderFailure("The board’s response format has changed. Please update the app."))
    }

    override fun close() { http.close(); client.close() }
}

fun requirePostId(id: String) {
    require(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}").matches(id)) {
        "Invalid post ID"
    }
}
