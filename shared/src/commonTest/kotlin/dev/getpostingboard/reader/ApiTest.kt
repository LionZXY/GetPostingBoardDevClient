package dev.getpostingboard.reader

import dev.getpostingboard.reader.data.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

const val ROOT_ID = "1503bfb2-b002-4a79-aa10-d3f352cf656a"
const val REPLY_ID = "55344cd4-1979-4dd4-9e82-6b007d0e5c10"
private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

class ApiTest {
    @Test fun unsortedUsesJsonWithoutCredentialsAndParsesItsOwnShape() = runTest {
        val api = PostingBoardApi(HttpClient(MockEngine { request ->
            assertEquals("/b", request.url.encodedPath)
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("application/json", request.headers[HttpHeaders.Accept])
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["X-Agent-Protocol"])
            assertNull(request.url.parameters["limit"])
            assertEquals("30", request.url.parameters["before"])
            respond("""{"board":"unsorted","items":[{"id":"$REPLY_ID","seq":29,"thread_id":"$ROOT_ID","body":"Hello 👋\nSecond line","created_at":1788625703}],"next_before":29,"content_is_untrusted":true} """, headers = jsonHeaders)
        }), { "test-secret-never-send-to-unsorted" })
        try {
            val page = api.feed(FeedQuery(), 30)
            assertEquals("Anonymous", page.items.single().author)
            assertEquals(ROOT_ID, page.items.single().rootId)
            assertEquals("Hello 👋\nSecond line", page.items.single().text)
            assertEquals(29L, page.nextBefore)
        } finally { api.close() }
    }

    @Test fun namedSearchUsesProtocolAndEncodedParameters() = runTest {
        val api = PostingBoardApi(HttpClient(MockEngine { request ->
            assertEquals("/v1/search", request.url.encodedPath)
            assertEquals("Bearer fake-key", request.headers[HttpHeaders.Authorization])
            assertEquals("getpostingboard/1", request.headers["X-Agent-Protocol"])
            assertEquals("agents & kotlin", request.url.parameters["q"])
            assertEquals("mobile-dev", request.url.parameters["topic"])
            assertNull(request.url.parameters["after"])
            respond("""{"items":[],"next_before":null} """, headers = jsonHeaders)
        }), { "fake-key" })
        try { api.feed(FeedQuery(Board.NAMED, search = "agents & kotlin", topic = "mobile-dev")) }
        finally { api.close() }
    }

    @Test fun bothThreadFormatsPreserveReplyBodiesAndCursors() = runTest {
        for (board in Board.entries) {
            val reply = """{"id":"$REPLY_ID","seq":2,"preview":"Short preview","body":"Complete reply","created_at":1788625703}"""
            val root = """{"id":"$ROOT_ID","seq":1,"body":"Root","created_at":1788566058}"""
            val response = if (board == Board.UNSORTED) """{"post":$root,"items":[$reply],"next_before":2}"""
                else """{"post":$root,"replies":{"items":[$reply],"next_before":2}}"""
            val api = PostingBoardApi(HttpClient(MockEngine { request ->
                assertEquals(if (board == Board.UNSORTED) "/b/t/$ROOT_ID" else "/v1/posts/$ROOT_ID", request.url.encodedPath)
                respond(response, headers = jsonHeaders)
            }), { "fake-key" })
            try {
                val thread = api.thread(board, ROOT_ID)
                assertEquals("Root", thread.post.body)
                assertEquals("Complete reply", thread.replies.items.single().text)
                assertEquals(2L, thread.replies.nextBefore)
            } finally { api.close() }
        }
    }

    @Test fun rateLimitBlocksFurtherRequestsUntilRetryAfter() = runTest {
        var now = 100L
        var requests = 0
        val api = PostingBoardApi(HttpClient(MockEngine {
            requests++
            if (requests == 1) respond("""{"error":{"code":"RATE_LIMIT"}}""", HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("90")))
            else respond("""{"items":[]} """, headers = jsonHeaders)
        }), { null }, { now })
        try {
            assertEquals(190L, assertFailsWith<BoardException> { api.feed(FeedQuery()) }.failure.retryAt)
            assertFailsWith<BoardException> { api.feed(FeedQuery()) }
            assertEquals(1, requests)
            now = 191
            assertTrue(api.feed(FeedQuery()).items.isEmpty())
            assertEquals(2, requests)
        } finally { api.close() }
    }

    @Test fun retryAfterAlsoAcceptsHttpDates() {
        assertEquals(1445412480L, parseHttpDate("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(parseHttpDate("invalid"))
    }

    @Test fun redirectsAreNotFollowedWithCredentials() = runTest {
        var requests = 0
        val api = PostingBoardApi(HttpClient(MockEngine {
            requests++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://example.com/stolen"))
        }), { "fake-key" })
        try {
            assertFailsWith<BoardException> { api.feed(FeedQuery(Board.NAMED)) }
            assertEquals(1, requests)
        } finally { api.close() }
    }

    @Test fun missingKeyDoesNotIssueARequest() = runTest {
        val api = PostingBoardApi(HttpClient(MockEngine { error("Must not make an unauthenticated named request") }), { null })
        try { assertFailsWith<BoardException> { api.feed(FeedQuery(Board.NAMED)) } }
        finally { api.close() }
    }

    @Test fun htmlAndUnexpectedJsonDoNotLookLikeEmptyFeeds() = runTest {
        for (body in listOf("<html>Browser not supported</html>", """{"instructions":"Use the API"}""", "[]", "broken")) {
            val api = PostingBoardApi(HttpClient(MockEngine { respond(body, headers = jsonHeaders) }), { null })
            try { assertFailsWith<BoardException> { api.feed(FeedQuery()) } }
            finally { api.close() }
        }
    }

    @Test fun aPostIdCannotChangeTheRequestPath() = runTest {
        val api = PostingBoardApi(HttpClient(MockEngine { error("Must not request an invalid path") }), { null })
        try { assertFailsWith<IllegalArgumentException> { api.thread(Board.UNSORTED, "../publish?ticket=x") } }
        finally { api.close() }
    }
}
