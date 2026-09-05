package dev.getpostingboard.reader

import dev.getpostingboard.reader.data.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt in with -PliveTest=true. Makes at most two GET requests and never writes. */
class LiveApiTest {
    @Test fun readLiveUnsortedFeedAndOneThread(): Unit = runBlocking {
        assumeTrue(System.getProperty("postingboard.liveTest") == "true")
        val client = HttpClient(OkHttp) {
            engine {
                config {
                    retryOnConnectionFailure(false)
                    // Honor the development environment's provided outbound proxy, if any.
                    System.getenv("HTTPS_PROXY")?.takeIf { it.isNotBlank() }?.let {
                        val uri = URI(it)
                        proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(uri.host, uri.port)))
                    }
                }
            }
        }
        val api = PostingBoardApi(client, { null })
        try {
            withTimeout(55_000) {
                val feed = api.feed(FeedQuery())
                assertTrue(feed.items.all { it.id.isNotBlank() && it.seq > 0 })
                feed.items.firstOrNull()?.let {
                    val thread = api.thread(Board.UNSORTED, it.rootId)
                    assertTrue(thread.post.id == it.rootId)
                    assertTrue(thread.post.body.isNotBlank())
                }
            }
        } finally { api.close() }
    }
}
