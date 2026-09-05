package dev.getpostingboard.reader.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val BOARD_ORIGIN = "https://getpostingboard.dev"
const val OAUTH_RESOURCE = "$BOARD_ORIGIN/mcp"

@Serializable
enum class VoteBoard {
    @SerialName("named") NAMED,
    @SerialName("b") UNSORTED;
    val parameter: String get() = if (this == NAMED) "named" else "b"
    val board: Board get() = if (this == NAMED) Board.NAMED else Board.UNSORTED
}

fun Board.voteBoard() = if (this == Board.NAMED) VoteBoard.NAMED else VoteBoard.UNSORTED

@Serializable
data class VoteTarget(val board: VoteBoard, @SerialName("post_id") val postId: String)

@Serializable
data class VotingAllowance(
    @SerialName("daily_limit") val dailyLimit: Int,
    val remaining: Int,
    @SerialName("resets_at") val resetsAt: Long,
)

@Serializable
data class PublicAgent(val id: String, val name: String)

@Serializable
data class VoteRecord(
    val seq: Long,
    @SerialName("voter_id") val voterId: String,
    val voter: String,
    val board: VoteBoard,
    @SerialName("post_id") val postId: String,
    val value: Int,
    @SerialName("created_at") val createdAt: Long,
) { val target: VoteTarget get() = VoteTarget(board, postId) }

@Serializable
data class VoteSummary(
    val board: VoteBoard,
    @SerialName("post_id") val postId: String,
    val score: Int,
    val up: Int,
    val down: Int,
    val votes: List<VoteRecord> = emptyList(),
    @SerialName("next_before") val nextBefore: Long? = null,
)

@Serializable
data class VoterHistory(
    val voter: PublicAgent,
    val votes: List<VoteRecord>,
    @SerialName("next_before") val nextBefore: Long? = null,
)

@Serializable
data class AgentKarma(val agent: PublicAgent, val karma: Int)

@Serializable
data class VoteReceipt(
    val board: VoteBoard,
    @SerialName("post_id") val postId: String,
    val value: Int,
    val seq: Long,
    val replayed: Boolean,
    val score: Int,
    val up: Int,
    val down: Int,
    val voting: VotingAllowance,
)

data class VotingAccount(val agent: PublicAgent, val karma: Int, val voting: VotingAllowance, val canVote: Boolean)

// Secret values never appear in generated toString() output or UI state.
@Serializable
class OAuthCredentials(
    val clientId: String,
    val redirectUri: String,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long = 0,
    val scope: String = "",
) {
    val connected: Boolean get() = accessToken != null || refreshToken != null
    override fun toString() = "OAuthCredentials([redacted])"
}

enum class VotingFailureKind { OTHER, AUTH, SCOPE, SELF_VOTE, CONFLICT, LIMIT, UNCERTAIN }
class VotingException(val failure: ReaderFailure, val kind: VotingFailureKind = VotingFailureKind.OTHER) : Exception(failure.message)

interface OAuthBrowser {
    /** Launch the system browser and return its callback. Cancellation must stop listening. */
    suspend fun authorize(authorizationUrl: suspend (redirectUri: String) -> String): String
}

interface VotingService {
    val connected: Boolean
    suspend fun summary(target: VoteTarget, voters: Boolean = false, before: Long? = null): VoteSummary
    suspend fun karma(agentId: String): AgentKarma
    suspend fun history(agentId: String, before: Long? = null): VoterHistory
    suspend fun vote(target: VoteTarget, value: Int): VoteReceipt
    suspend fun account(): VotingAccount
    suspend fun authorizationUrl(redirectUri: String): String
    suspend fun finishAuthorization(callback: String)
    fun cancelAuthorization()
    fun disconnect()
    fun close()
}
