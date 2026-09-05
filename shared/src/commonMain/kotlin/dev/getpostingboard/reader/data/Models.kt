package dev.getpostingboard.reader.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Board { UNSORTED, NAMED }

@Serializable
enum class FeedMode { THREADS, ACTIVITY }

@Serializable
data class FeedQuery(
    val board: Board = Board.UNSORTED,
    val mode: FeedMode = FeedMode.THREADS,
    val search: String = "",
    val topic: String = "",
)

@Serializable
data class Post(
    val id: String,
    val seq: Long,
    @SerialName("thread_id") val threadId: String? = null,
    val author: String = "Anonymous",
    val title: String = "",
    val topic: String = "unsorted",
    val preview: String = "",
    val body: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("agent_id") val agentId: String? = null,
    val score: Int? = null,
) {
    val rootId: String get() = threadId ?: id
    val text: String get() = body.ifBlank { preview }
}

@Serializable
data class PostPage(
    val items: List<Post> = emptyList(),
    @SerialName("next_before") val nextBefore: Long? = null,
)

@Serializable
internal data class ThreadPayload(
    val post: Post,
    val replies: PostPage? = null,
    val items: List<Post> = emptyList(),
    @SerialName("next_before") val nextBefore: Long? = null,
)

@Serializable
data class ThreadPage(val post: Post, val replies: PostPage)

@Serializable
data class CachedFeed(val savedAt: Long, val page: PostPage)

@Serializable
data class CachedThread(val savedAt: Long, val page: ThreadPage)

data class ReaderFailure(val message: String, val retryAt: Long? = null)

class BoardException(val failure: ReaderFailure) : Exception(failure.message)

@Serializable
data class RegistrationRequest(
    val name: String,
    val description: String,
    @SerialName("discovered_via") val discoveredVia: String = "posting-board-reader",
    @SerialName("participation_basis") val participationBasis: String = "owner_directed",
)

@Serializable
class RegisteredAccount(val id: String, val name: String, @SerialName("api_key") val apiKey: String) {
    override fun toString(): String = "RegisteredAccount(id=$id, name=$name, apiKey=[redacted])"
}

class RegistrationOutcomeUnknownException : Exception(
    "Registration may have succeeded, but no usable key was received. The service cannot recover keys. " +
        "Contact the board operator before registering again.",
)

fun registrationFailure(name: String, description: String): ReaderFailure? = when {
    !Regex("[a-z0-9][a-z0-9-]{2,39}").matches(name) ->
        ReaderFailure("Use 3–40 lowercase letters, numbers, or hyphens, starting with a letter or number.")
    description.length > 240 -> ReaderFailure("Keep the description to 240 characters or fewer.")
    else -> null
}

interface BoardService {
    suspend fun feed(query: FeedQuery, before: Long? = null): PostPage
    suspend fun thread(board: Board, id: String, before: Long? = null): ThreadPage
    suspend fun fullPost(id: String): Post
    suspend fun validateKey(key: String)
    suspend fun register(request: RegistrationRequest): RegisteredAccount
    fun close()
}

interface ReaderCache {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
}

interface CredentialStore {
    val persistent: Boolean get() = false
    fun read(): String?
    fun write(key: String?)
    fun readOAuth(): OAuthCredentials? = null
    fun writeOAuth(value: OAuthCredentials?) { error("OAuth storage is unavailable") }
}

object NoCache : ReaderCache {
    override suspend fun read(key: String): String? = null
    override suspend fun write(key: String, value: String) = Unit
}
