package dev.getpostingboard.reader

import dev.getpostingboard.reader.data.*
import dev.getpostingboard.reader.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlin.test.*

class MemoryCredentials(var key: String? = null) : CredentialStore {
    var oauth: OAuthCredentials? = null
    override fun read() = key
    override fun write(key: String?) { this.key = key }
    override fun readOAuth() = oauth
    override fun writeOAuth(value: OAuthCredentials?) { oauth = value }
}

open class FakeService : BoardService {
    var feedHandler: suspend (FeedQuery, Long?) -> PostPage = { _, _ -> PostPage() }
    var threadHandler: suspend (Board, String, Long?) -> ThreadPage = { _, _, _ -> ThreadPage(post(1), PostPage()) }
    override suspend fun feed(query: FeedQuery, before: Long?) = feedHandler(query, before)
    override suspend fun thread(board: Board, id: String, before: Long?) = threadHandler(board, id, before)
    override suspend fun fullPost(id: String) = post(2).copy(id = id, body = "Full reply")
    override suspend fun validateKey(key: String) = Unit
    override suspend fun register(request: RegistrationRequest) = RegisteredAccount(ROOT_ID, request.name, "gpb_test_key")
    override fun close() = Unit
}

fun post(seq: Long, body: String = "Message $seq") = Post(if (seq == 1L) ROOT_ID else "00000000-0000-0000-0000-${seq.toString().padStart(12, '0')}", seq, body = body)

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderStoreTest {
    @Test fun paginationDeduplicatesOverlappingPagesAndStopsRepeatedCursor() = runTest {
        val service = FakeService().apply {
            feedHandler = { _, before -> if (before == null) PostPage(listOf(post(3), post(2)), 2)
                else PostPage(listOf(post(2), post(1)), 2) }
        }
        val store = ReaderStore(ReaderRepository(service, NoCache), MemoryCredentials(), backgroundScope)
        runCurrent(); store.loadMore(); runCurrent()
        assertEquals(listOf(3L, 2L, 1L), store.state.value.posts.map { it.seq })
        assertNull(store.state.value.nextBefore)
    }

    @Test fun switchingFeedsCannotBeOverwrittenByALateResult() = runTest {
        val stale = CompletableDeferred<PostPage>()
        val service = FakeService().apply {
            feedHandler = { query, _ -> if (query.board == Board.UNSORTED) withContext(NonCancellable) { stale.await() }
                else PostPage(listOf(post(2, "Named board"))) }
        }
        val store = ReaderStore(ReaderRepository(service, NoCache), MemoryCredentials("fake-key"), backgroundScope)
        runCurrent(); store.selectBoard(Board.NAMED); runCurrent()
        stale.complete(PostPage(listOf(post(1, "Old result")))); runCurrent()
        assertEquals("Named board", store.state.value.posts.single().body)
        assertEquals(Board.NAMED, store.state.value.query.board)
    }

    @Test fun offlineStartupShowsCacheAndAnHonestError() = runTest {
        val service = FakeService().apply { feedHandler = { _, _ -> error("offline") } }
        val cache = object : ReaderCache {
            override suspend fun read(key: String) = BoardJson.encodeToString(CachedFeed(100, PostPage(listOf(post(1)))))
            override suspend fun write(key: String, value: String) = Unit
        }
        val store = ReaderStore(ReaderRepository(service, cache), MemoryCredentials(), backgroundScope)
        runCurrent()
        assertEquals(1, store.state.value.posts.size)
        assertEquals(100L, store.state.value.cachedAt)
        assertNotNull(store.state.value.failure)
        assertFalse(store.state.value.loading)
    }

    @Test fun failedRefreshKeepsAlreadyVisibleContent() = runTest {
        val service = FakeService().apply { feedHandler = { _, _ -> PostPage(listOf(post(1))) } }
        val store = ReaderStore(ReaderRepository(service, NoCache), MemoryCredentials(), backgroundScope)
        runCurrent()
        service.feedHandler = { _, _ -> error("offline") }
        store.refresh(); runCurrent()
        assertEquals(ROOT_ID, store.state.value.posts.single().id)
        assertFalse(store.state.value.paginationFailed)
    }

    @Test fun openingReplyLoadsRootAndSortsRepliesChronologically() = runTest {
        val service = FakeService().apply {
            threadHandler = { _, id, _ ->
                assertEquals(ROOT_ID, id)
                ThreadPage(post(1), PostPage(listOf(post(3), post(2))))
            }
        }
        val store = ReaderStore(ReaderRepository(service, NoCache), MemoryCredentials(), backgroundScope)
        runCurrent(); store.openThread(post(3).copy(threadId = ROOT_ID)); runCurrent()
        assertEquals(listOf(2L, 3L), store.state.value.detail!!.replies.map { it.seq })
        store.closeThread()
        assertNull(store.state.value.detail)
    }

    @Test fun namedRepliesCanLoadTheirFullBody() = runTest {
        val store = ReaderStore(ReaderRepository(FakeService(), NoCache), MemoryCredentials("fake-key"), backgroundScope)
        runCurrent(); store.selectBoard(Board.NAMED); runCurrent()
        store.openThread(post(1)); runCurrent()
        store.expandReply(post(2)); runCurrent()
        assertEquals("Full reply", store.state.value.detail!!.expandedReplies.values.single().body)
        assertTrue(store.state.value.detail!!.expandingReplies.isEmpty())
    }

    @Test fun localSearchKeepsPaginationAndDoesNotFetchAgain() = runTest {
        var calls = 0
        val service = FakeService().apply { feedHandler = { _, _ -> calls++; PostPage(listOf(post(2, "Kotlin agents"), post(1, "Coffee")), 1) } }
        val store = ReaderStore(ReaderRepository(service, NoCache), MemoryCredentials(), backgroundScope)
        runCurrent(); store.search("KOTLIN agents", ""); runCurrent()
        assertEquals(1, calls)
        assertEquals(1, store.state.value.visiblePosts().size)
        assertEquals(1L, store.state.value.nextBefore)
        store.search("", "")
        assertEquals(2, store.state.value.visiblePosts().size)
    }

    @Test fun credentialsAreSavedOnlyAfterSuccessfulValidation() = runTest {
        val credentials = MemoryCredentials()
        val service = object : FakeService() {
            override suspend fun validateKey(key: String) { throw BoardException(ReaderFailure("Invalid key")) }
        }
        val store = ReaderStore(ReaderRepository(service, NoCache), credentials, backgroundScope)
        runCurrent(); store.connect("bad-key"); runCurrent()
        assertNull(credentials.key)
        assertFalse(store.state.value.connected)
        assertNotNull(store.state.value.connectionFailure)
    }
}
