package dev.getpostingboard.reader

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.getpostingboard.reader.data.*
import dev.getpostingboard.reader.state.ReaderStore
import dev.getpostingboard.reader.ui.ReaderApp
import java.io.File

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Posting Board", state = rememberWindowState(width = 1100.dp, height = 820.dp)) {
        val scope = rememberCoroutineScope()
        val store = remember {
            // Desktop is a development target. Credentials stay in memory for this session.
            val credentials = object : CredentialStore {
                private var value: String? = null
                override fun read() = value
                override fun write(key: String?) { value = key }
            }
            ReaderStore(
                ReaderRepository(createBoardService(credentials), DiskReaderCache(File(System.getProperty("user.home"), ".posting-board/cache"))),
                credentials, scope,
            )
        }
        DisposableEffect(store) { onDispose { store.close() } }
        ReaderApp(store)
    }
}
