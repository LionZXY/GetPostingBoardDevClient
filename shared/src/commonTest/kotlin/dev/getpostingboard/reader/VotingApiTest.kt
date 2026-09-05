package dev.getpostingboard.reader

import dev.getpostingboard.reader.data.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

private val votingJson = headersOf(HttpHeaders.ContentType, "application/json")
const val AGENT_ID = "bb5036a4-de7d-416d-aade-478b8b2b3a73"
val votingTarget = VoteTarget(VoteBoard.NAMED, ROOT_ID)
const val ALLOWANCE_JSON = """{"daily_limit":20,"remaining":19,"resets_at":86400}"""
val RECEIPT_JSON = """{"board":"named","post_id":"$ROOT_ID","value":1,"seq":11,"replayed":false,"score":3,"up":4,"down":1,"voting":$ALLOWANCE_JSON}"""

fun oauthCredentials(scope: String = "board:read board:write", expiresAt: Long = 10_000) = OAuthCredentials(
    "test-client", "dev.getpostingboard.reader:/oauth/callback", "oauth-access", "oauth-refresh", expiresAt, scope,
)

class VotingApiTest {
    @Test fun publicVoteReadsNeverSendCredentialsAndUseExclusiveQueries() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val credentials = MemoryCredentials("gpb_never_public").apply { oauth = oauthCredentials() }
        val api = VotingApi(HttpClient(MockEngine { request ->
            requests += request
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/jovan", request.url.encodedPath)
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["X-Agent-Protocol"])
            val params = request.url.parameters
            val body = when {
                params["agent"] != null -> """{"agent":{"id":"$AGENT_ID","name":"reader"},"karma":-2}"""
                params["voter"] != null -> """{"voter":{"id":"$AGENT_ID","name":"reader"},"votes":[],"next_before":null}"""
                else -> """{"board":"${params["board"]}","post_id":"$ROOT_ID","score":-2,"up":1,"down":3,"votes":[],"next_before":null}"""
            }
            respond(body, headers = votingJson)
        }), credentials, { 100 })
        try {
            assertEquals(-2, api.summary(votingTarget).score)
            assertEquals(setOf("board", "post_id"), requests.last().url.parameters.names())
            api.summary(VoteTarget(VoteBoard.UNSORTED, ROOT_ID), true, 42)
            assertEquals("b", requests.last().url.parameters["board"])
            assertEquals("true", requests.last().url.parameters["voters"])
            assertEquals("42", requests.last().url.parameters["before"])
            assertEquals(-2, api.karma(AGENT_ID).karma)
            assertEquals(setOf("agent"), requests.last().url.parameters.names())
            api.history(AGENT_ID, 20)
            assertEquals(setOf("voter", "before", "limit"), requests.last().url.parameters.names())
        } finally { api.close() }
    }

    @Test fun votingUsesOnlyOAuthAndTheExactPublicWritePayload() = runTest {
        val credentials = MemoryCredentials("gpb_must_not_vote").apply { oauth = oauthCredentials() }
        val api = VotingApi(HttpClient(MockEngine { request ->
            assertEquals("https://getpostingboard.dev/jovan", request.url.toString())
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("Bearer oauth-access", request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["Idempotency-Key"])
            val json = BoardJson.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(setOf("board", "post_id", "value"), json.keys)
            assertEquals("named", json["board"]?.jsonPrimitive?.content)
            assertEquals(ROOT_ID, json["post_id"]?.jsonPrimitive?.content)
            assertEquals(1, json["value"]?.jsonPrimitive?.int)
            respond(RECEIPT_JSON, headers = votingJson)
        }), credentials, { 100 })
        try { assertEquals(19, api.vote(votingTarget, 1).voting.remaining) }
        finally { api.close() }
    }

    @Test fun rawKeysAndReadonlyConnectionsCannotVote() = runTest {
        val credentials = MemoryCredentials("gpb_key")
        val api = VotingApi(HttpClient(MockEngine { error("No voting request is allowed") }), credentials, { 100 })
        try {
            assertEquals(VotingFailureKind.AUTH, assertFailsWith<VotingException> { api.vote(votingTarget, 1) }.kind)
            credentials.oauth = oauthCredentials("board:read")
            assertEquals(VotingFailureKind.SCOPE, assertFailsWith<VotingException> { api.vote(votingTarget, 1) }.kind)
            assertFailsWith<IllegalArgumentException> { api.vote(votingTarget, 0) }
            assertFailsWith<IllegalArgumentException> { api.summary(votingTarget.copy(postId = "../oauth/token")) }
        } finally { api.close() }
    }

    @Test fun expiredTokensRefreshAndPersistRotatedCredentialsBeforeVoting() = runTest {
        val credentials = MemoryCredentials().apply { oauth = oauthCredentials(expiresAt = 99) }
        var calls = 0
        val api = VotingApi(HttpClient(MockEngine { request ->
            calls++
            if (request.url.encodedPath == "/oauth/token") {
                assertNull(request.headers[HttpHeaders.Authorization])
                val params = parseQueryString(String(request.body.toByteArray()))
                assertEquals("refresh_token", params["grant_type"])
                assertEquals("oauth-refresh", params["refresh_token"])
                assertEquals(OAUTH_RESOURCE, params["resource"])
                respond("""{"access_token":"renewed","refresh_token":"rotated","expires_in":3600,"token_type":"Bearer","scope":"board:read board:write"}""", headers = votingJson)
            } else {
                assertEquals("Bearer renewed", request.headers[HttpHeaders.Authorization])
                assertEquals("rotated", credentials.oauth?.refreshToken)
                respond(RECEIPT_JSON, headers = votingJson)
            }
        }), credentials, { 100 })
        try {
            api.vote(votingTarget, 1)
            assertEquals(2, calls)
            assertFalse(credentials.oauth.toString().contains("rotated"))
        } finally { api.close() }
    }

    @Test fun oauthUsesPkceAndValidatesStateIssuerAndCallbackBeforeExchanging() = runTest {
        val credentials = MemoryCredentials("gpb_private")
        var tokenCalls = 0
        var challenge = ""
        val api = VotingApi(HttpClient(MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Authorization])
            if (request.url.encodedPath == "/oauth/register") {
                respond("""{"client_id":"new-client","token_endpoint_auth_method":"none"}""", HttpStatusCode.Created, votingJson)
            } else {
                tokenCalls++
                val form = parseQueryString(String(request.body.toByteArray()))
                assertEquals("code", form["code"])
                assertEquals(challenge, pkceChallenge(form["code_verifier"]!!))
                assertEquals(OAUTH_RESOURCE, form["resource"])
                assertEquals("new-client", form["client_id"])
                respond("""{"access_token":"new-token","refresh_token":"new-refresh","token_type":"Bearer","expires_in":3600}""", headers = votingJson)
            }
        }), credentials, { 100 })
        try {
            val redirect = "dev.getpostingboard.reader:/oauth/callback"
            val auth = Url(api.authorizationUrl(redirect))
            challenge = auth.parameters["code_challenge"]!!
            assertEquals("S256", auth.parameters["code_challenge_method"])
            assertEquals("board:read board:write", auth.parameters["scope"])
            assertFalse(auth.toString().contains("gpb_private"))
            val callback = "$redirect?code=code&state=${auth.parameters["state"]}&iss=https%3A%2F%2Fgetpostingboard.dev"
            assertFailsWith<VotingException> { api.finishAuthorization(callback.replace("state=", "state=wrong")) }
            assertFailsWith<VotingException> { api.finishAuthorization(callback.replace("getpostingboard.dev", "example.com")) }
            assertFailsWith<VotingException> { api.finishAuthorization(callback.replace("/oauth/callback", "/other")) }
            assertFailsWith<VotingException> { api.finishAuthorization("$callback&code=second") }
            assertEquals(0, tokenCalls)
            api.finishAuthorization(callback)
            assertEquals("new-token", credentials.oauth?.accessToken)
            assertEquals(1, tokenCalls)
            assertFailsWith<VotingException> { api.finishAuthorization(callback) }
        } finally { api.close() }
    }

    @Test fun pkceMatchesTheRfc7636VectorAndCredentialsStayRedacted() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertEquals(43, secureOAuthRandom().length)
        val stored = OAuthCredentials.decodeFromStorage(oauthCredentials().encodeForStorage())
        assertEquals("oauth-access", stored.accessToken)
        assertFalse(stored.toString().contains("oauth-access"))
    }

    @Test fun mcpAccountSupportsStructuredJsonAndEventStreams() = runTest {
        for (sse in listOf(false, true)) {
            val credentials = MemoryCredentials().apply { oauth = oauthCredentials() }
            val api = VotingApi(HttpClient(MockEngine { request ->
                assertEquals("/mcp", request.url.encodedPath)
                assertEquals("Bearer oauth-access", request.headers[HttpHeaders.Authorization])
                val json = BoardJson.parseToJsonElement((request.body as TextContent).text).jsonObject
                val id = json["id"]?.jsonPrimitive?.int
                if (id == null) respond("", HttpStatusCode.Accepted)
                else {
                    val result = if (json["method"]?.jsonPrimitive?.content == "initialize") """{"protocolVersion":"2025-03-26","capabilities":{}}"""
                    else """{"structuredContent":{"id":"$AGENT_ID","name":"reader","karma":-3,"voting":$ALLOWANCE_JSON},"isError":false}"""
                    val body = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
                    respond(if (sse) "event: message\ndata: $body\n\n" else body,
                        headers = headersOf(HttpHeaders.ContentType, if (sse) "text/event-stream" else "application/json"))
                }
            }), credentials, { 100 })
            try {
                val account = api.account()
                assertEquals(-3, account.karma)
                assertTrue(account.canVote)
                assertEquals(19, account.voting.remaining)
            } finally { api.close() }
        }
    }

    @Test fun immutableConflictsAndRateLimitsAreActionable() = runTest {
        for (status in listOf(HttpStatusCode.Conflict, HttpStatusCode.TooManyRequests, HttpStatusCode.Unauthorized)) {
            var calls = 0
            val credentials = MemoryCredentials().apply { oauth = oauthCredentials() }
            val api = VotingApi(HttpClient(MockEngine {
                calls++
                respond("{}", status, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("90")))
            }), credentials, { 100 })
            try {
                val failure = assertFailsWith<VotingException> { api.vote(votingTarget, 1) }
                assertEquals(190L, failure.failure.retryAt)
                assertEquals(when (status) { HttpStatusCode.Conflict -> VotingFailureKind.CONFLICT
                    HttpStatusCode.Unauthorized -> VotingFailureKind.AUTH; else -> VotingFailureKind.LIMIT }, failure.kind)
                assertFailsWith<VotingException> { api.summary(votingTarget) }
                assertEquals(1, calls)
            } finally { api.close() }
        }
    }

    @Test fun lostOrMalformedVoteReceiptsAreUncertainAndNeverAutomaticallyRetried() = runTest {
        for (mode in 0..3) {
            var calls = 0
            val credentials = MemoryCredentials().apply { oauth = oauthCredentials() }
            val api = VotingApi(HttpClient(MockEngine {
                calls++
                when (mode) {
                    0 -> error("Connection lost")
                    1 -> respond("{}", headers = votingJson)
                    2 -> respond("<html>Unknown</html>", headers = headersOf(HttpHeaders.ContentType, "text/html"))
                    else -> respond("{}", HttpStatusCode.ServiceUnavailable, votingJson)
                }
            }), credentials, { 100 })
            try {
                assertEquals(VotingFailureKind.UNCERTAIN, assertFailsWith<VotingException> { api.vote(votingTarget, 1) }.kind)
                assertEquals(1, calls)
            } finally { api.close() }
        }
    }

    @Test fun cancelledAuthorizationAndDisconnectCannotBeReplayed() = runTest {
        val credentials = MemoryCredentials().apply { oauth = oauthCredentials() }
        val api = VotingApi(HttpClient(MockEngine { error("No HTTP request expected") }), credentials, { 100 })
        try {
            val url = Url(api.authorizationUrl(credentials.oauth!!.redirectUri))
            api.cancelAuthorization()
            assertFailsWith<VotingException> { api.finishAuthorization("${credentials.oauth!!.redirectUri}?code=code&state=${url.parameters["state"]}&iss=https%3A%2F%2Fgetpostingboard.dev") }
            api.disconnect()
            assertFalse(api.connected)
            assertNull(credentials.oauth)
        } finally { api.close() }
    }
}
