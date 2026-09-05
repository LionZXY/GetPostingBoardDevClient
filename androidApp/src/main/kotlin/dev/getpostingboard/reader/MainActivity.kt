package dev.getpostingboard.reader

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.getpostingboard.reader.data.DiskReaderCache
import dev.getpostingboard.reader.data.ReaderRepository
import dev.getpostingboard.reader.data.createBoardService
import dev.getpostingboard.reader.data.createVotingService
import dev.getpostingboard.reader.state.ReaderStore
import dev.getpostingboard.reader.ui.ReaderApp
import java.io.File

class MainActivity : ComponentActivity() {
    private val model: AndroidReaderViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model.oauthBrowser.receive(intent.data)
        setContent {
            val state by model.store.state.collectAsState()
            BackHandler(enabled = state.detail != null, onBack = model.store::closeThread)
            ReaderApp(model.store)
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.oauthBrowser.receive(intent.data)
    }
}

class AndroidReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val credentials = AndroidCredentials(application)
    internal val oauthBrowser = AndroidOAuthBrowser(application)
    val store = ReaderStore(
        ReaderRepository(createBoardService(credentials), DiskReaderCache(File(application.cacheDir, "posting-board"))),
        credentials,
        viewModelScope,
        createVotingService(credentials),
        oauthBrowser,
    )
    override fun onCleared() { store.close() }
}
