package dev.getpostingboard.reader.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.time.Clock

expect fun secureOAuthRandom(): String
expect fun pkceChallenge(verifier: String): String

/** Public vote reads, OAuth account linking, and explicit immutable votes. */
class VotingApi(
    private val client: HttpClient,
    private val credentials: CredentialStore,
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) : VotingService {
    private val http = client.config {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 25_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 25_000 }
    }
    private val tokenMutex = Mutex()
    private val mcpMutex = Mutex()
    private var retryAt: Long? = null
    private var authVersion = 0
    private var pending: PendingAuthorization? = null
    private var mcpSession: String? = null
    private var mcpInitialized = false
    private var rpcId = 0
    override val connected: Boolean get() = credentials.readOAuth()?.connected == true

    override suspend fun summary(target: VoteTarget, voters: Boolean, before: Long?): VoteSummary {
        requirePostId(target.postId)
        require(before == null || voters && before > 0)
        val params = buildMap {
            put("board", target.board.parameter); put("post_id", target.postId)
            if (voters) { put("voters", "true"); put("limit", "20") }
            before?.let { put("before", it.toString()) }
        }
        return decode<VoteSummary>(send("/jovan", params = params).body).also {
            checkResponse(it.board == target.board && it.postId == target.postId && it.up >= 0 && it.down >= 0)
            checkResponse(it.votes.all { vote -> vote.target == target && vote.value in listOf(-1, 1) })
        }
    }

    override suspend fun karma(agentId: String): AgentKarma {
        requirePostId(agentId)
        return decode<AgentKarma>(send("/jovan", params = mapOf("agent" to agentId)).body).also {
            checkResponse(it.agent.id == agentId)
        }
    }

    override suspend fun history(agentId: String, before: Long?): VoterHistory {
        requirePostId(agentId); require(before == null || before > 0)
        return decode<VoterHistory>(send("/jovan", params = buildMap {
            put("voter", agentId); put("limit", "20"); before?.let { put("before", it.toString()) }
        }).body).also {
            checkResponse(it.voter.id == agentId && it.votes.all { v -> v.voterId == agentId && v.value in listOf(-1, 1) })
        }
    }

    override suspend fun vote(target: VoteTarget, value: Int): VoteReceipt {
        requirePostId(target.postId); require(value == 1 || value == -1)
        val auth = token()
        if ("board:write" !in auth.scope.split(' ')) throw VotingException(
            ReaderFailure("Reconnect voting and allow public votes on the account-link page."), VotingFailureKind.SCOPE,
        )
        val body = try {
            send("/jovan", HttpMethod.Post, token = auth.accessToken, json = buildJsonObject {
                put("board", target.board.parameter); put("post_id", target.postId); put("value", value)
            }).body
        } catch (e: CancellationException) { throw e }
        catch (e: VotingException) { throw e }
        catch (_: Exception) { throw uncertainVote() }
        return try {
            decode<VoteReceipt>(body).also {
                checkResponse(it.board == target.board && it.postId == target.postId && it.value == value)
                checkAllowance(it.voting)
            }
        } catch (_: Exception) { throw uncertainVote() }
    }

    override suspend fun authorizationUrl(redirectUri: String): String {
        require(redirectUri == "dev.getpostingboard.reader:/oauth/callback" ||
            Regex("http://127\\.0\\.0\\.1:[0-9]{1,5}/oauth/callback").matches(redirectUri))
        val version = ++authVersion
        pending = null
        val stored = credentials.readOAuth()
        val registration = if (stored != null && stored.redirectUri == redirectUri) stored else {
            val result = parseObject(send("/oauth/register", HttpMethod.Post, json = buildJsonObject {
                put("client_name", "Posting Board Reader")
                putJsonArray("redirect_uris") { add(redirectUri) }
                put("token_endpoint_auth_method", "none")
                putJsonArray("grant_types") { add("authorization_code"); add("refresh_token") }
                putJsonArray("response_types") { add("code") }
                put("scope", "board:read board:write")
            }).body)
            val clientId = result.string("client_id")
            checkResponse(clientId.isNotBlank() && result.string("token_endpoint_auth_method") == "none")
            OAuthCredentials(clientId, redirectUri).also {
                currentCoroutineContext().ensureActive()
                checkResponse(version == authVersion)
                // Do not replace a working connection until the new grant succeeds.
                if (stored?.connected != true) saveCredentials(it)
            }
        }
        currentCoroutineContext().ensureActive()
        checkResponse(version == authVersion)
        val verifier = secureOAuthRandom()
        val state = secureOAuthRandom()
        pending = PendingAuthorization(registration.clientId, redirectUri, verifier, state, version)
        return URLBuilder("$BOARD_ORIGIN/oauth/authorize").apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", registration.clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("scope", "board:read board:write")
            parameters.append("resource", OAUTH_RESOURCE)
            parameters.append("code_challenge", pkceChallenge(verifier))
            parameters.append("code_challenge_method", "S256")
            parameters.append("state", state)
        }.buildString()
    }

    override suspend fun finishAuthorization(callback: String) {
        val request = pending ?: throw VotingException(ReaderFailure("Start the voting connection again."), VotingFailureKind.AUTH)
        // Exact redirect, unique state/code and issuer checks precede any token exchange.
        if (callback.substringBefore('?') != request.redirectUri || '#' in callback) invalidCallback()
        val params = Url(callback).parameters
        if (params.getAll("state") != listOf(request.state) || params.getAll("iss") != listOf(BOARD_ORIGIN)) invalidCallback()
        if (params["error"] != null) {
            pending = null
            throw VotingException(ReaderFailure("Voting connection was not approved. You can try connecting again."), VotingFailureKind.AUTH)
        }
        val code = params.getAll("code")?.singleOrNull()?.takeIf { it.isNotBlank() } ?: invalidCallback()
        pending = null
        val session = exchangeToken(parameters {
            append("grant_type", "authorization_code"); append("code", code)
            append("client_id", request.clientId); append("redirect_uri", request.redirectUri)
            append("code_verifier", request.verifier); append("resource", OAUTH_RESOURCE)
        }, OAuthCredentials(request.clientId, request.redirectUri), "board:read board:write")
        currentCoroutineContext().ensureActive()
        if (request.version != authVersion) invalidCallback()
        saveCredentials(session)
        mcpInitialized = false; mcpSession = null
    }

    override fun cancelAuthorization() { ++authVersion; pending = null }

    override fun disconnect() {
        cancelAuthorization()
        credentials.writeOAuth(null)
        mcpInitialized = false; mcpSession = null
    }

    private suspend fun token(): OAuthCredentials = tokenMutex.withLock {
        val stored = credentials.readOAuth()?.takeIf { it.connected }
            ?: throw VotingException(ReaderFailure("Connect a voting account first."), VotingFailureKind.AUTH)
        if (stored.accessToken != null && stored.expiresAt > now() + 60) return@withLock stored
        val refresh = stored.refreshToken ?: throw VotingException(
            ReaderFailure("Your voting connection expired. Reconnect voting."), VotingFailureKind.AUTH,
        )
        val version = authVersion
        val renewed = exchangeToken(parameters {
            append("grant_type", "refresh_token"); append("refresh_token", refresh)
            append("client_id", stored.clientId); append("resource", OAUTH_RESOURCE)
        }, stored, stored.scope)
        currentCoroutineContext().ensureActive()
        if (version != authVersion) throw VotingException(ReaderFailure("Voting was disconnected."), VotingFailureKind.AUTH)
        saveCredentials(renewed)
        renewed
    }

    private suspend fun exchangeToken(form: Parameters, previous: OAuthCredentials, defaultScope: String): OAuthCredentials {
        val result = parseObject(send("/oauth/token", HttpMethod.Post, form = form).body)
        val access = result.string("access_token")
        val expires = result["expires_in"]?.jsonPrimitive?.longOrNull ?: 0
        val refresh = result["refresh_token"]?.jsonPrimitive?.contentOrNull ?: previous.refreshToken
        checkResponse(result.string("token_type").equals("Bearer", true) && access.isNotBlank() && expires > 0)
        checkResponse(access.none { it.isWhitespace() || it.isISOControl() })
        return OAuthCredentials(previous.clientId, previous.redirectUri, access, refresh, now() + expires,
            result["scope"]?.jsonPrimitive?.contentOrNull ?: defaultScope)
    }

    override suspend fun account(): VotingAccount = mcpMutex.withLock {
        val auth = token()
        if (!mcpInitialized) {
            val result = rpc("initialize", buildJsonObject {
                put("protocolVersion", "2025-03-26")
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") { put("name", "PostingBoardReader"); put("version", "1.1") }
            }, auth.accessToken!!)
            checkResponse(result.string("protocolVersion") == "2025-03-26")
            send("/mcp", HttpMethod.Post, token = auth.accessToken, json = buildJsonObject {
                put("jsonrpc", "2.0"); put("method", "notifications/initialized")
            }, mcp = true)
            mcpInitialized = true
        }
        val result = rpc("tools/call", buildJsonObject {
            put("name", "get_my_agent"); putJsonObject("arguments") {}
        }, auth.accessToken!!)
        if (result["isError"]?.jsonPrimitive?.booleanOrNull == true) {
            throw VotingException(ReaderFailure("Could not read the voting account. Reconnect or retry."), VotingFailureKind.AUTH)
        }
        val data = result["structuredContent"] as? JsonObject ?: result["content"]?.jsonArray
            ?.firstOrNull { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "text" }
            ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let(::parseObject) ?: badResponse()
        val agent = data["agent"] as? JsonObject ?: data
        val allowance = data["voting"] ?: agent["voting"] ?: badResponse()
        val voting = decode<VotingAllowance>(allowance.toString()).also(::checkAllowance)
        val id = agent.string("id"); requirePostId(id)
        val karma = (data["karma"] ?: agent["karma"])?.jsonPrimitive?.intOrNull ?: badResponse()
        VotingAccount(PublicAgent(id, agent.string("name")), karma, voting, "board:write" in auth.scope.split(' '))
    }

    private suspend fun rpc(method: String, params: JsonObject, accessToken: String): JsonObject {
        val id = ++rpcId
        val response = send("/mcp", HttpMethod.Post, token = accessToken, json = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }, mcp = true)
        response.session?.let { mcpSession = it }
        val messages = if (response.contentType == "text/event-stream") {
            response.body.replace("\r\n", "\n").split("\n\n").mapNotNull { event ->
                event.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trimStart() }
                    .takeIf { it.isNotBlank() }?.let(::parseObject)
            }
        } else listOf(parseObject(response.body))
        val message = messages.firstOrNull { it["id"]?.jsonPrimitive?.intOrNull == id } ?: badResponse()
        checkResponse("error" !in message)
        return message["result"] as? JsonObject ?: badResponse()
    }

    private suspend fun send(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        token: String? = null,
        params: Map<String, String> = emptyMap(),
        json: JsonObject? = null,
        form: Parameters? = null,
        mcp: Boolean = false,
    ): Response {
        retryAt?.takeIf { it > now() }?.let {
            throw VotingException(ReaderFailure("Posting Board asked us to wait before trying again.", it), VotingFailureKind.LIMIT)
        }
        val response = http.request("$BOARD_ORIGIN$path") {
            this.method = method
            header(HttpHeaders.Accept, if (mcp) "application/json, text/event-stream" else "application/json")
            header(HttpHeaders.UserAgent, "PostingBoardReader/1.1 (Compose Multiplatform)")
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            if (mcp) {
                header("MCP-Protocol-Version", "2025-03-26")
                mcpSession?.let { header("Mcp-Session-Id", it) }
            }
            params.forEach { (key, value) -> parameter(key, value) }
            json?.let { contentType(ContentType.Application.Json); setBody(it.toString()) }
            form?.let { setBody(FormDataContent(it)) }
        }
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val retry = response.headers[HttpHeaders.RetryAfter]?.let {
                it.toLongOrNull()?.coerceAtLeast(0)?.let { seconds -> now() + seconds } ?: parseHttpDate(it)
            }
            if (retry != null || response.status.value == 429) retryAt = retry?.coerceAtLeast(now()) ?: now() + 60
            if (path == "/jovan" && method == HttpMethod.Post && response.status.value >= 500) throw uncertainVote()
            val code = runCatching { parseObject(body)["error"]?.jsonObject?.string("code") }.getOrNull()
            val (message, kind) = when (response.status.value) {
                401 -> "Your voting connection expired or was revoked. Reconnect voting." to VotingFailureKind.AUTH
                403 -> if (code?.contains("SELF") == true)
                    "You cannot vote on your own named posts or replies." to VotingFailureKind.SELF_VOTE
                    else "Voting was denied. Reconnect with permission to vote; named self-votes are not allowed." to VotingFailureKind.SCOPE
                404 -> "This post or account is no longer available." to VotingFailureKind.OTHER
                409 -> "You already voted on this message. Votes cannot be changed or removed." to VotingFailureKind.CONFLICT
                429 -> "The voting or request limit was reached. Wait before trying again." to VotingFailureKind.LIMIT
                in 300..399 -> "Posting Board returned an unexpected redirect." to VotingFailureKind.OTHER
                in 500..599 -> "Posting Board is temporarily unavailable. Try again later." to VotingFailureKind.OTHER
                else -> if (path.startsWith("/oauth/")) "Could not connect voting. Start the connection again." to VotingFailureKind.AUTH
                    else "The voting request could not be completed (HTTP ${response.status.value})." to VotingFailureKind.OTHER
            }
            throw VotingException(ReaderFailure(message, retryAt?.takeIf { it > now() }), kind)
        }
        val type = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (body.isNotBlank() && type != "application/json" && !(mcp && type == "text/event-stream")) {
            if (path == "/jovan" && method == HttpMethod.Post) throw uncertainVote()
            badResponse()
        }
        return Response(body, type, response.headers["Mcp-Session-Id"])
    }

    private fun saveCredentials(value: OAuthCredentials) {
        try { credentials.writeOAuth(value) }
        catch (_: Exception) { throw VotingException(ReaderFailure("Could not save the voting connection securely on this device. Reconnect to try again.")) }
    }

    private fun checkAllowance(value: VotingAllowance) = checkResponse(value.dailyLimit > 0 && value.remaining in 0..value.dailyLimit && value.resetsAt > 0)
    private inline fun <reified T> decode(body: String): T = try { BoardJson.decodeFromString<T>(body) }
        catch (_: Exception) { badResponse() }
    private fun parseObject(body: String): JsonObject = try { BoardJson.parseToJsonElement(body).jsonObject }
        catch (_: Exception) { badResponse() }
    private fun JsonObject.string(name: String): String = (get(name) as? JsonPrimitive)?.contentOrNull ?: ""
    private fun checkResponse(valid: Boolean) { if (!valid) badResponse() }
    private fun badResponse(): Nothing = throw VotingException(ReaderFailure("Posting Board returned unreadable voting data. Try refreshing."))
    private fun invalidCallback(): Nothing = throw VotingException(ReaderFailure("The sign-in response did not match this connection. Start again."), VotingFailureKind.AUTH)
    private fun uncertainVote() = VotingException(ReaderFailure("The vote may have been recorded. Retry the same vote to check; it will not spend another vote."), VotingFailureKind.UNCERTAIN)
    override fun close() { cancelAuthorization(); http.close(); client.close() }
    private class PendingAuthorization(val clientId: String, val redirectUri: String, val verifier: String, val state: String, val version: Int)
    private class Response(val body: String, val contentType: String, val session: String?)
}
