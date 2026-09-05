package dev.getpostingboard.reader

import dev.getpostingboard.reader.data.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class RegistrationApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun registrationPostsTheContractWithoutAnExistingCredential() = runTest {
        val api = PostingBoardApi(HttpClient(MockEngine { request ->
            assertEquals("https://getpostingboard.dev/v1/agents", request.url.toString())
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("application/json", request.headers[HttpHeaders.Accept])
            assertEquals("getpostingboard/1", request.headers["X-Agent-Protocol"])
            assertNull(request.headers[HttpHeaders.Authorization])
            val content = request.body as TextContent
            assertEquals(ContentType.Application.Json, content.contentType)
            val body = BoardJson.parseToJsonElement(content.text).jsonObject
            assertEquals("reader-test", body["name"]?.jsonPrimitive?.content)
            assertEquals("A \"reader\"\nDescription", body["description"]?.jsonPrimitive?.content)
            assertEquals("owner_directed", body["participation_basis"]?.jsonPrimitive?.content)
            assertEquals("posting-board-reader", body["discovered_via"]?.jsonPrimitive?.content)
            respond("""{"id":"$ROOT_ID","name":"reader-test","api_key":"gpb_new_key","instructions":"untrusted text"}""",
                HttpStatusCode.Created, jsonHeaders)
        }), { "existing-secret-must-not-be-sent" })
        try {
            val account = api.register(RegistrationRequest("reader-test", "A \"reader\"\nDescription"))
            assertEquals("gpb_new_key", account.apiKey)
            assertFalse(account.toString().contains(account.apiKey))
        } finally { api.close() }
    }

    @Test fun invalidAccountDetailsAreRejectedBeforeSending() = runTest {
        val api = PostingBoardApi(HttpClient(MockEngine { error("Must not send invalid details") }), { null })
        try {
            for (name in listOf("ab", "Uppercase", "-reader", "with spaces", "a".repeat(41))) {
                assertFailsWith<BoardException> { api.register(RegistrationRequest(name, "")) }
            }
            assertFailsWith<BoardException> { api.register(RegistrationRequest("reader", "a".repeat(241))) }
        } finally { api.close() }
    }

    @Test fun takenNamesHaveAnActionableErrorWithoutReflectingTheResponse() = runTest {
        val api = PostingBoardApi(HttpClient(MockEngine {
            respond("""{"error":{"code":"NAME_TAKEN","message":"gpb_secret"}}""", HttpStatusCode.Conflict, jsonHeaders)
        }), { null })
        try {
            val failure = assertFailsWith<BoardException> { api.register(RegistrationRequest("reader", "")) }.failure
            assertTrue(failure.message.contains("already taken"))
            assertFalse(failure.message.contains("gpb_secret"))
        } finally { api.close() }
    }

    @Test fun registrationHonorsRetryAfterWithoutSendingAnotherRequest() = runTest {
        var requests = 0
        var now = 100L
        val api = PostingBoardApi(HttpClient(MockEngine {
            requests++
            respond("{}", HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("90")))
        }), { null }, { now })
        try {
            assertEquals(190L, assertFailsWith<BoardException> { api.register(RegistrationRequest("reader", "")) }.failure.retryAt)
            assertFailsWith<BoardException> { api.register(RegistrationRequest("reader", "")) }
            assertFailsWith<BoardException> { api.feed(FeedQuery()) }
            assertEquals(1, requests)
            now = 191
            assertFailsWith<BoardException> { api.register(RegistrationRequest("reader", "")) }
            assertEquals(2, requests)
        } finally { api.close() }
    }

    @Test fun unreadableOrIncompleteReceiptsDoNotPromptAnotherRegistration() = runTest {
        for (body in listOf("broken", "{}", "[]", """{"id":"id","name":"reader","api_key":""}""",
            """{"id":"id","name":"reader","api_key":"bad key"}""",
            """{"id":"id","name":"someone-else","api_key":"gpb_key"}""")) {
            var requests = 0
            val api = PostingBoardApi(HttpClient(MockEngine {
                requests++
                respond(body, HttpStatusCode.Created, jsonHeaders)
            }), { null })
            try {
                assertFailsWith<RegistrationOutcomeUnknownException> { api.register(RegistrationRequest("reader", "")) }
                assertEquals(1, requests)
            } finally { api.close() }
        }
    }

    @Test fun networkFailureLeavesRegistrationOutcomeUnknown() = runTest {
        val api = PostingBoardApi(HttpClient(MockEngine { error("Connection lost") }), { null })
        try { assertFailsWith<RegistrationOutcomeUnknownException> { api.register(RegistrationRequest("reader", "")) } }
        finally { api.close() }
    }

    @Test fun registrationDoesNotFollowRedirectsOrAcceptHtml() = runTest {
        for (status in listOf(HttpStatusCode.TemporaryRedirect, HttpStatusCode.Created)) {
            var requests = 0
            val api = PostingBoardApi(HttpClient(MockEngine {
                requests++
                respond("<html>Not a key</html>", status,
                    headersOf(HttpHeaders.ContentType to listOf("text/html"), HttpHeaders.Location to listOf("https://example.com")))
            }), { null })
            try {
                if (status == HttpStatusCode.Created) {
                    assertFailsWith<RegistrationOutcomeUnknownException> { api.register(RegistrationRequest("reader", "")) }
                } else assertFailsWith<BoardException> { api.register(RegistrationRequest("reader", "")) }
                assertEquals(1, requests)
            } finally { api.close() }
        }
    }
}
