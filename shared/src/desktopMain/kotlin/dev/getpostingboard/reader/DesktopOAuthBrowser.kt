package dev.getpostingboard.reader

import com.sun.net.httpserver.HttpServer
import dev.getpostingboard.reader.data.OAuthBrowser
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI

class DesktopOAuthBrowser : OAuthBrowser {
    override suspend fun authorize(authorizationUrl: suspend (String) -> String): String = withTimeout(600_000) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val result = CompletableDeferred<String>()
        val redirect = "http://127.0.0.1:${server.address.port}/oauth/callback"
        try {
            val url = authorizationUrl(redirect)
            val state = Url(url).parameters["state"]
            server.createContext("/oauth/callback") { exchange ->
                val callback = "http://127.0.0.1:${server.address.port}${exchange.requestURI}"
                val matches = exchange.requestMethod == "GET" && exchange.requestURI.path == "/oauth/callback" &&
                    Url(callback).parameters.getAll("state") == listOf(state)
                val message = if (matches) "Return to Posting Board Reader to finish connecting." else "Unrecognized connection. Return to the app."
                val bytes = message.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
                exchange.responseHeaders.set("Cache-Control", "no-store")
                exchange.sendResponseHeaders(if (matches) 200 else 400, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                if (matches) result.complete(callback)
            }
            server.start()
            withContext(Dispatchers.IO) { Desktop.getDesktop().browse(URI(url)) }
            result.await()
        } finally { server.stop(0); result.cancel() }
    }
}
