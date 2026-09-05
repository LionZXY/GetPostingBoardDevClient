package dev.getpostingboard.reader

import dev.getpostingboard.reader.data.*
import dev.getpostingboard.reader.state.VotingStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Clock

const val OTHER_AGENT_ID = "05bf94f7-700c-4802-92aa-2d06e5726f44"

open class FakeVotingService : VotingService {
    override var connected = true
    var currentAccount = VotingAccount(PublicAgent(AGENT_ID, "reader"), 7,
        VotingAllowance(20, 19, Clock.System.now().epochSeconds + 3600), true)
    var summaryHandler: suspend (VoteTarget, Boolean, Long?) -> VoteSummary = { target, voters, _ ->
        VoteSummary(target.board, target.postId, 3, 4, 1, if (voters) listOf(
            VoteRecord(10, OTHER_AGENT_ID, "Ada", target.board, target.postId, 1, Clock.System.now().epochSeconds - 600),
        ) else emptyList())
    }
    var historyHandler: suspend (String, Long?) -> VoterHistory = { id, _ -> VoterHistory(PublicAgent(id, "Ada"), emptyList()) }
    var voteHandler: suspend (VoteTarget, Int) -> VoteReceipt = { target, value ->
        VoteReceipt(target.board, target.postId, value, 11, false, 3 + value, 4 + if (value == 1) 1 else 0,
            1 + if (value == -1) 1 else 0, currentAccount.voting.copy(remaining = 18))
    }
    override suspend fun summary(target: VoteTarget, voters: Boolean, before: Long?) = summaryHandler(target, voters, before)
    override suspend fun karma(agentId: String) = AgentKarma(PublicAgent(agentId, "Ada"), -2)
    override suspend fun history(agentId: String, before: Long?) = historyHandler(agentId, before)
    override suspend fun vote(target: VoteTarget, value: Int) = voteHandler(target, value)
    override suspend fun account() = currentAccount
    override suspend fun authorizationUrl(redirectUri: String) = "$BOARD_ORIGIN/oauth/authorize?state=test"
    override suspend fun finishAuthorization(callback: String) { connected = true }
    override fun cancelAuthorization() = Unit
    override fun disconnect() { connected = false }
    override fun close() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class VotingStoreTest {
    @Test fun voteUpdatesScoreAllowanceAndLocksTheChoice() = runTest {
        var calls = 0
        val service = FakeVotingService().apply {
            val original = voteHandler
            voteHandler = { target, value -> calls++; original(target, value) }
        }
        val store = VotingStore(service, backgroundScope, now = { 100 })
        runCurrent(); store.openVotes(Board.NAMED, post(1)); runCurrent()
        store.castVote(1); store.castVote(-1); runCurrent()
        assertEquals(1, calls)
        assertEquals(4, store.state.value.panel?.summary?.score)
        assertEquals(18, store.state.value.account?.voting?.remaining)
        assertEquals(1, store.state.value.ownVotes[votingTarget])
        store.castVote(-1); store.castVote(1); runCurrent()
        assertEquals(1, calls)
    }

    @Test fun uncertainVotesAllowOnlyAnIdenticalRetry() = runTest {
        val values = mutableListOf<Int>()
        val service = FakeVotingService().apply {
            val original = voteHandler
            voteHandler = { target, value ->
                values += value
                if (values.size == 1) throw VotingException(ReaderFailure("Unknown result"), VotingFailureKind.UNCERTAIN)
                original(target, value).copy(replayed = true)
            }
        }
        val store = VotingStore(service, backgroundScope, now = { 100 })
        runCurrent(); store.openVotes(Board.NAMED, post(1)); runCurrent()
        store.castVote(1); runCurrent()
        assertEquals(1, store.state.value.pendingVotes[votingTarget])
        store.castVote(-1); runCurrent()
        assertEquals(listOf(1), values)
        store.castVote(1); runCurrent()
        assertEquals(listOf(1, 1), values)
        assertTrue(store.state.value.pendingVotes.isEmpty())
        assertEquals(1, store.state.value.ownVotes[votingTarget])
    }

    @Test fun selfVotesUnreadMessagesReadonlyAndExhaustedAccountsAreBlocked() = runTest {
        var calls = 0
        val service = FakeVotingService().apply { voteHandler = { _, _ -> calls++; error("Must not vote") } }
        val store = VotingStore(service, backgroundScope, now = { 100 })
        runCurrent(); store.openVotes(Board.NAMED, post(1).copy(agentId = AGENT_ID)); runCurrent()
        store.castVote(1); runCurrent()
        store.openVotes(Board.NAMED, post(1).copy(body = "", preview = "Partial message")); runCurrent()
        store.castVote(1); runCurrent()
        store.openVotes(Board.NAMED, post(1)); runCurrent()
        service.currentAccount = service.currentAccount.copy(canVote = false)
        store.refreshAccount(); runCurrent(); store.castVote(1); runCurrent()
        service.currentAccount = service.currentAccount.copy(canVote = true, voting = VotingAllowance(20, 0, 200))
        store.refreshAccount(); runCurrent(); store.castVote(1); runCurrent()
        assertEquals(0, calls)
    }

    @Test fun midnightAndRetryAfterUseServerLimitsWithoutAutomaticWrites() = runTest {
        var now = 100L
        var calls = 0
        val service = FakeVotingService().apply {
            currentAccount = currentAccount.copy(voting = VotingAllowance(20, 0, 200))
            voteHandler = { _, _ -> calls++; throw VotingException(ReaderFailure("Wait", 300), VotingFailureKind.LIMIT) }
        }
        val store = VotingStore(service, backgroundScope, now = { now })
        runCurrent(); store.openVotes(Board.UNSORTED, post(1)); runCurrent()
        store.castVote(-1); runCurrent(); assertEquals(0, calls)
        now = 201; store.castVote(-1); runCurrent(); assertEquals(1, calls)
        store.castVote(-1); runCurrent(); assertEquals(1, calls)
        now = 301; runCurrent(); assertEquals(1, calls)
        store.castVote(-1); runCurrent(); assertEquals(2, calls)
    }

    @Test fun immutableConflictDisablesBothDirections() = runTest {
        var calls = 0
        val service = FakeVotingService().apply {
            voteHandler = { _, _ -> calls++; throw VotingException(ReaderFailure("Already voted"), VotingFailureKind.CONFLICT) }
        }
        val store = VotingStore(service, backgroundScope)
        runCurrent(); store.openVotes(Board.NAMED, post(1)); runCurrent()
        store.castVote(-1); runCurrent(); store.castVote(1); runCurrent()
        assertEquals(1, calls)
        assertTrue(votingTarget in store.state.value.immutableTargets)
    }

    @Test fun votersAreOptInPaginatedAndDeduplicated() = runTest {
        val requests = mutableListOf<Pair<Boolean, Long?>>()
        fun vote(seq: Long) = VoteRecord(seq, AGENT_ID, "reader", VoteBoard.NAMED, ROOT_ID, 1, 100)
        val service = FakeVotingService().apply {
            summaryHandler = { target, voters, before ->
                requests += voters to before
                VoteSummary(target.board, target.postId, 2, 2, 0,
                    if (!voters) emptyList() else if (before == null) listOf(vote(20)) else listOf(vote(20), vote(10)), 20)
            }
        }
        val store = VotingStore(service, backgroundScope)
        runCurrent(); store.openVotes(Board.NAMED, post(1)); runCurrent()
        assertEquals(listOf<Pair<Boolean, Long?>>(false to null), requests)
        store.refreshVotes(showVoters = true); runCurrent()
        store.refreshVotes(showVoters = true, more = true); runCurrent()
        assertEquals(listOf(20L, 10L), store.state.value.panel?.summary?.votes?.map { it.seq })
        assertNull(store.state.value.panel?.summary?.nextBefore)
        assertEquals(1, store.state.value.ownVotes[votingTarget])
    }

    @Test fun outgoingHistoryKeepsItsOwnCursorAndKarmaCanBeNegative() = runTest {
        val service = FakeVotingService().apply {
            historyHandler = { id, before ->
                val vote = VoteRecord(before ?: 20, id, "Ada", VoteBoard.UNSORTED, ROOT_ID, -1, 100)
                VoterHistory(PublicAgent(id, "Ada"), listOf(vote), if (before == null) 10 else null)
            }
        }
        val store = VotingStore(service, backgroundScope)
        store.openProfile(PublicAgent(OTHER_AGENT_ID, "Ada")); runCurrent()
        assertEquals(-2, store.state.value.profile?.karma)
        assertFalse(store.state.value.profile!!.historyShown)
        store.loadHistory(); runCurrent(); store.loadHistory(more = true); runCurrent()
        assertEquals(listOf(20L, 10L), store.state.value.profile?.votes?.map { it.seq })
        assertNull(store.state.value.profile?.nextBefore)
    }

    @Test fun lateVoteAndSummaryCannotRestoreDisconnectedOrClosedState() = runTest {
        val receipt = CompletableDeferred<VoteReceipt>()
        val service = FakeVotingService().apply { voteHandler = { _, _ -> withContext(NonCancellable) { receipt.await() } } }
        val store = VotingStore(service, backgroundScope)
        runCurrent(); store.openVotes(Board.NAMED, post(1)); runCurrent()
        store.castVote(1); runCurrent(); store.disconnect(); store.closeVotes(); runCurrent()
        receipt.complete(VoteReceipt(VoteBoard.NAMED, ROOT_ID, 1, 1, false, 4, 4, 0, VotingAllowance(20, 18, 200)))
        runCurrent()
        assertNull(store.state.value.account)
        assertNull(store.state.value.panel)
        assertTrue(store.state.value.ownVotes.isEmpty())
        assertFalse(store.state.value.connected)
    }

    @Test fun browserLinkingLoadsTheAccountAndCanBeCancelled() = runTest {
        val service = FakeVotingService().apply { connected = false }
        val callback = CompletableDeferred<String>()
        val browser = object : OAuthBrowser {
            override suspend fun authorize(authorizationUrl: suspend (String) -> String): String {
                authorizationUrl("dev.getpostingboard.reader:/oauth/callback")
                return callback.await()
            }
        }
        val store = VotingStore(service, backgroundScope, browser)
        store.connect(); runCurrent(); assertTrue(store.state.value.connecting)
        store.cancelConnection(); runCurrent(); assertFalse(store.state.value.connecting)
        assertFalse(store.state.value.connected)
        callback.complete("callback")
        store.connect(); runCurrent()
        assertTrue(store.state.value.connected)
        assertEquals(AGENT_ID, store.state.value.account?.agent?.id)
    }
}
