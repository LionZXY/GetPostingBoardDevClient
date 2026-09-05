package dev.getpostingboard.reader.state

import dev.getpostingboard.reader.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

data class VotePanel(
    val target: VoteTarget,
    val post: Post,
    val summary: VoteSummary? = null,
    val loading: Boolean = false,
    val votersShown: Boolean = false,
    val loadingMore: Boolean = false,
    val failure: ReaderFailure? = null,
)

data class AgentPanel(
    val agent: PublicAgent,
    val karma: Int? = null,
    val loading: Boolean = true,
    val historyShown: Boolean = false,
    val votes: List<VoteRecord> = emptyList(),
    val nextBefore: Long? = null,
    val loadingHistory: Boolean = false,
    val failure: ReaderFailure? = null,
)

data class VotingState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val loadingAccount: Boolean = false,
    val account: VotingAccount? = null,
    val connectionFailure: ReaderFailure? = null,
    val panel: VotePanel? = null,
    val profile: AgentPanel? = null,
    val summaries: Map<VoteTarget, VoteSummary> = emptyMap(),
    val ownVotes: Map<VoteTarget, Int> = emptyMap(),
    val pendingVotes: Map<VoteTarget, Int> = emptyMap(),
    val immutableTargets: Set<VoteTarget> = emptySet(),
    val casting: VoteTarget? = null,
    val voteFailures: Map<VoteTarget, ReaderFailure> = emptyMap(),
)

class VotingStore(
    private val service: VotingService?,
    private val scope: CoroutineScope,
    private val browser: OAuthBrowser? = null,
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val mutable = MutableStateFlow(VotingState(connected = service?.connected == true))
    val state = mutable.asStateFlow()
    val available: Boolean get() = service != null
    private var accountJob: Job? = null
    private var connectJob: Job? = null
    private var panelJob: Job? = null
    private var profileJob: Job? = null
    private var historyJob: Job? = null
    private var voteJob: Job? = null
    private var accountVersion = 0
    private var panelVersion = 0
    private var profileVersion = 0

    init { if (mutable.value.connected) refreshAccount() }

    fun connect() {
        if (service == null || mutable.value.connecting || mutable.value.casting != null) return
        if (browser == null) {
            mutable.update { it.copy(connectionFailure = ReaderFailure("Browser sign-in is unavailable on this device.")) }
            return
        }
        val version = ++accountVersion
        accountJob?.cancel()
        mutable.update { it.copy(connecting = true, loadingAccount = false, connectionFailure = null) }
        connectJob = scope.launch {
            try {
                val callback = browser.authorize(service::authorizationUrl)
                service.finishAuthorization(callback)
                if (version != accountVersion) return@launch
                // The browser may have linked a different account. Forget all identity-specific state.
                mutable.update { it.copy(connected = true, account = null, ownVotes = emptyMap(), pendingVotes = emptyMap(),
                    immutableTargets = emptySet(), voteFailures = emptyMap()) }
                val account = service.account()
                if (version == accountVersion) mutable.update { it.copy(connecting = false, account = account) }
            } catch (_: TimeoutCancellationException) {
                if (version == accountVersion) mutable.update { it.copy(connecting = false,
                    connectionFailure = ReaderFailure("Sign-in timed out. Start the connection again.")) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (version == accountVersion) mutable.update { it.copy(connecting = false,
                    connected = service.connected, connectionFailure = e.votingFailure()) }
            } finally { if (version == accountVersion) service.cancelAuthorization() }
        }
    }

    fun cancelConnection() {
        ++accountVersion
        connectJob?.cancel(); accountJob?.cancel(); service?.cancelAuthorization()
        mutable.update { it.copy(connecting = false, loadingAccount = false) }
    }

    fun refreshAccount() {
        if (service?.connected != true || mutable.value.connecting || mutable.value.casting != null) return
        accountJob?.cancel()
        val version = ++accountVersion
        mutable.update { it.copy(loadingAccount = true, connectionFailure = null) }
        accountJob = scope.launch {
            try {
                val account = service.account()
                if (version == accountVersion) mutable.update {
                    val sameAccount = it.account?.agent?.id == account.agent.id
                    it.copy(connected = true, loadingAccount = false, account = account,
                        ownVotes = if (sameAccount) it.ownVotes else emptyMap(),
                        pendingVotes = if (sameAccount) it.pendingVotes else emptyMap(),
                        immutableTargets = if (sameAccount) it.immutableTargets else emptySet())
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (version == accountVersion) mutable.update { it.copy(loadingAccount = false, connectionFailure = e.votingFailure(),
                    account = if (e is VotingException && e.kind == VotingFailureKind.AUTH) null else it.account) }
            }
        }
    }

    fun disconnect() {
        cancelConnection(); voteJob?.cancel()
        try {
            service?.disconnect()
            mutable.update { it.copy(connected = false, account = null, casting = null, connectionFailure = null,
                ownVotes = emptyMap(), pendingVotes = emptyMap(), immutableTargets = emptySet(), voteFailures = emptyMap()) }
        } catch (_: Exception) {
            mutable.update { it.copy(casting = null, connectionFailure = ReaderFailure("Could not remove the saved voting connection. Try again.")) }
        }
    }

    fun openVotes(board: Board, post: Post) {
        if (service == null) return
        closeVotes()
        val target = VoteTarget(board.voteBoard(), post.id)
        mutable.update { it.copy(panel = VotePanel(target, post, it.summaries[target])) }
        refreshVotes()
    }

    fun closeVotes() { ++panelVersion; panelJob?.cancel(); mutable.update { it.copy(panel = null) } }

    fun refreshVotes(showVoters: Boolean = mutable.value.panel?.votersShown == true, more: Boolean = false) {
        val panel = mutable.value.panel ?: return
        if (service == null || more && (panel.loadingMore || panel.loading)) return
        val before = if (more) panel.summary?.nextBefore ?: return else null
        panelJob?.cancel()
        val version = ++panelVersion
        mutable.update { it.copy(panel = it.panel?.copy(loading = !more, loadingMore = more, votersShown = showVoters, failure = null)) }
        panelJob = scope.launch {
            try {
                val page = service.summary(panel.target, showVoters, before)
                if (version != panelVersion) return@launch
                val summary = if (more) page.copy(
                    votes = (panel.summary.orEmptyVotes() + page.votes).distinctBy { it.seq },
                    nextBefore = page.nextBefore?.takeIf { it < before!! && page.votes.isNotEmpty() },
                ) else page
                mutable.update { it.copy(panel = it.panel?.copy(summary = summary, loading = false, loadingMore = false),
                    summaries = it.summaries + (panel.target to summary)) }
                recordOwnVotes(summary.votes)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (version == panelVersion) mutable.update { it.copy(panel = it.panel?.copy(loading = false, loadingMore = false, failure = e.votingFailure())) }
            }
        }
    }

    fun openProfile(agent: PublicAgent) {
        if (service == null) return
        closeProfile()
        mutable.update { it.copy(profile = AgentPanel(agent)) }
        refreshProfile()
    }

    fun closeProfile() {
        ++profileVersion; profileJob?.cancel(); historyJob?.cancel()
        mutable.update { it.copy(profile = null) }
    }

    fun refreshProfile() {
        val profile = mutable.value.profile ?: return
        if (service == null) return
        profileJob?.cancel()
        val version = profileVersion
        mutable.update { it.copy(profile = it.profile?.copy(loading = true, failure = null)) }
        profileJob = scope.launch {
            try {
                val karma = service.karma(profile.agent.id)
                if (version == profileVersion) mutable.update { it.copy(profile = it.profile?.copy(agent = karma.agent, karma = karma.karma, loading = false)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (version == profileVersion) mutable.update { it.copy(profile = it.profile?.copy(loading = false, failure = e.votingFailure())) }
            }
        }
    }

    fun loadHistory(more: Boolean = false) {
        val profile = mutable.value.profile ?: return
        if (service == null || profile.loadingHistory) return
        val before = if (more) profile.nextBefore ?: return else null
        val version = profileVersion
        mutable.update { it.copy(profile = it.profile?.copy(historyShown = true, loadingHistory = true, failure = null)) }
        historyJob = scope.launch {
            try {
                val page = service.history(profile.agent.id, before)
                if (version != profileVersion) return@launch
                mutable.update { it.copy(profile = it.profile?.copy(loadingHistory = false,
                    votes = ((if (more) profile.votes else emptyList()) + page.votes).distinctBy { it.seq },
                    nextBefore = page.nextBefore?.takeIf { next -> before == null || next < before && page.votes.isNotEmpty() })) }
                recordOwnVotes(page.votes)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (version == profileVersion) mutable.update { it.copy(profile = it.profile?.copy(loadingHistory = false, failure = e.votingFailure())) }
            }
        }
    }

    fun castVote(value: Int) {
        val snapshot = mutable.value
        val panel = snapshot.panel ?: return
        val account = snapshot.account ?: return
        val target = panel.target
        if (service == null || panel.post.body.isBlank() || value !in listOf(-1, 1) || snapshot.casting != null || snapshot.connecting ||
            snapshot.loadingAccount || !account.canVote || target in snapshot.ownVotes || target in snapshot.immutableTargets ||
            snapshot.pendingVotes[target]?.let { it != value } == true ||
            target.board == VoteBoard.NAMED && panel.post.agentId == account.agent.id ||
            account.voting.remaining == 0 && now() < account.voting.resetsAt ||
            (snapshot.voteFailures[target]?.retryAt ?: 0) > now()) return
        val version = accountVersion
        mutable.update { it.copy(casting = target, pendingVotes = it.pendingVotes + (target to value), voteFailures = it.voteFailures - target) }
        voteJob = scope.launch {
            try {
                val receipt = service.vote(target, value)
                if (version != accountVersion) return@launch
                // Invalidate outstanding summary reads so an older score cannot overwrite this receipt.
                if (mutable.value.panel?.target == target) { ++panelVersion; panelJob?.cancel() }
                val summary = VoteSummary(receipt.board, receipt.postId, receipt.score, receipt.up, receipt.down)
                mutable.update { it.copy(casting = null, account = it.account?.copy(voting = receipt.voting),
                    ownVotes = it.ownVotes + (target to receipt.value), pendingVotes = it.pendingVotes - target,
                    summaries = it.summaries + (target to summary),
                    panel = it.panel?.let { p -> if (p.target == target) p.copy(summary = summary, loading = false,
                        loadingMore = false, votersShown = false, failure = null) else p }) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (version != accountVersion) return@launch
                val kind = (e as? VotingException)?.kind ?: VotingFailureKind.UNCERTAIN
                mutable.update { it.copy(casting = null, voteFailures = it.voteFailures + (target to e.votingFailure()),
                    pendingVotes = if (kind == VotingFailureKind.UNCERTAIN ||
                        target in snapshot.pendingVotes && kind != VotingFailureKind.CONFLICT) it.pendingVotes else it.pendingVotes - target,
                    immutableTargets = if (kind == VotingFailureKind.CONFLICT) it.immutableTargets + target else it.immutableTargets,
                    account = if (kind == VotingFailureKind.AUTH) null else it.account,
                    connectionFailure = if (kind == VotingFailureKind.AUTH || kind == VotingFailureKind.SCOPE) e.votingFailure() else it.connectionFailure) }
            }
        }
    }

    private fun recordOwnVotes(votes: List<VoteRecord>) {
        val accountId = mutable.value.account?.agent?.id ?: return
        val own = votes.filter { it.voterId == accountId }.associate { it.target to it.value }
        mutable.update { it.copy(ownVotes = it.ownVotes + own, pendingVotes = it.pendingVotes - own.keys) }
    }

    fun close() {
        ++accountVersion; ++panelVersion; ++profileVersion
        listOf(accountJob, connectJob, panelJob, profileJob, historyJob, voteJob).forEach { it?.cancel() }
        service?.close()
    }
}

private fun VoteSummary?.orEmptyVotes() = this?.votes.orEmpty()
private fun Exception.votingFailure(): ReaderFailure = when (this) {
    is VotingException -> failure
    else -> ReaderFailure("Couldn’t reach Posting Board. Check your connection and try again.")
}
