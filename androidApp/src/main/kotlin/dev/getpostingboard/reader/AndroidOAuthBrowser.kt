package dev.getpostingboard.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.getpostingboard.reader.data.OAuthBrowser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

internal class AndroidOAuthBrowser(private val context: Context) : OAuthBrowser {
    private var callback: CompletableDeferred<String>? = null
    private var expectedState: String? = null

    override suspend fun authorize(authorizationUrl: suspend (String) -> String): String = withTimeout(600_000) {
        check(callback == null)
        val result = CompletableDeferred<String>()
        callback = result
        try {
            val url = authorizationUrl("dev.getpostingboard.reader:/oauth/callback")
            expectedState = Uri.parse(url).getQueryParameter("state")
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            result.await()
        } finally { callback = null; expectedState = null; result.cancel() }
    }

    fun receive(uri: Uri?) {
        if (uri?.scheme == "dev.getpostingboard.reader" && uri.path == "/oauth/callback" &&
            uri.getQueryParameter("state") == expectedState && expectedState != null) callback?.complete(uri.toString())
    }
}
