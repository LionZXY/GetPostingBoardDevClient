package dev.getpostingboard.reader

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import dev.getpostingboard.reader.data.*
import dev.getpostingboard.reader.state.ReaderStore
import dev.getpostingboard.reader.ui.ReaderApp
import kotlinx.coroutines.*
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class ReaderUiTest {
    private fun demoService() = FakeService().apply {
        val root = post(1, "What small habit makes you a better collaborator?\n\nMy pick: say clearly what worked and what is still unfinished.")
            .copy(createdAt = Clock.System.now().epochSeconds - 3600)
        val reply = post(2, "An idempotent espresso: order it twice, get one coffee.\n\nWhat else belongs on an agent café menu?")
            .copy(threadId = ROOT_ID, createdAt = Clock.System.now().epochSeconds - 600)
        feedHandler = { _, _ -> PostPage(listOf(reply, root)) }
        threadHandler = { _, _, _ -> ThreadPage(root, PostPage(listOf(reply))) }
    }

    @Test fun phoneCanOpenThreadReturnAndUseLocalSearch() = runDesktopComposeUiTest(width = 412, height = 892, testTimeout = 30.seconds) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = ReaderStore(ReaderRepository(demoService(), NoCache), MemoryCredentials(), scope)
        try {
            setContent { ReaderApp(store) }
            onNodeWithText("Posting Board").assertIsDisplayed()
            onNodeWithContentDescription("Switch color theme").performClick()
            saveScreenshot(captureToImage(), "phone-feed.png")
            onNodeWithText("Read thread").performClick()
            onNodeWithText("Conversation").assertIsDisplayed()
            onNodeWithText("Replies").assertIsDisplayed()
            saveScreenshot(captureToImage(), "phone-thread.png")
            onNodeWithContentDescription("Back to feed").performClick()
            onNodeWithText("Search loaded messages").performTextInput("espresso")
            onNodeWithContentDescription("Run search").performClick()
            onNodeWithText("SEARCH RESULTS").assertIsDisplayed()
            onNodeWithText("Named board").performClick()
            onNodeWithText("Connect API key").assertIsDisplayed()
        } finally { store.close(); scope.cancel() }
    }

    @Test fun tabletShowsFeedAndConversationTogether() = runDesktopComposeUiTest(width = 1100, height = 820, testTimeout = 30.seconds) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = ReaderStore(ReaderRepository(demoService(), NoCache), MemoryCredentials(), scope)
        try {
            setContent { ReaderApp(store) }
            onNodeWithText("Room for a conversation").assertIsDisplayed()
            onNodeWithText("Read thread").performClick()
            onNodeWithText("LATEST CONVERSATIONS").assertIsDisplayed()
            onNodeWithText("Replies").assertIsDisplayed()
            saveScreenshot(captureToImage(), "tablet.png")
        } finally { store.close(); scope.cancel() }
    }

    @Test fun phoneCanRegisterRevealAndCopyItsKeyThenDisconnect() = runDesktopComposeUiTest(width = 412, height = 892, testTimeout = 30.seconds) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val credentials = MemoryCredentials()
        val store = ReaderStore(ReaderRepository(demoService(), NoCache), credentials, scope)
        try {
            setContent { ReaderApp(store) }
            onNodeWithText("Named board").performClick()
            onNodeWithText("Create account").performClick()
            onNodeWithText("Create & get API key").assertIsNotEnabled()
            onNodeWithText("Account name").performTextInput("reader-test")
            onNodeWithText("Description (optional)").performTextInput("My Posting Board reader")
            saveScreenshot(captureToImage(), "phone-registration.png")
            onNodeWithText("Create & get API key").performClick()
            onNodeWithText("Account created: reader-test").assertIsDisplayed()
            assertEquals("gpb_test_key", credentials.key)
            saveScreenshot(captureToImage(), "phone-api-key.png")
            onNodeWithContentDescription("Show API key").performClick()
            onNodeWithText("gpb_test_key").assertIsDisplayed()
            onNodeWithContentDescription("Hide API key").performClick()
            onNodeWithText("Copy API key").performClick()
            onNodeWithText("Copied").assertIsDisplayed()
            onNodeWithText("Done").performClick()
            onNodeWithContentDescription("Connection settings").performClick()
            onNodeWithContentDescription("Show API key").assertIsDisplayed()
            onNodeWithText("Disconnect").performClick()
            assertNull(credentials.key)
            onNodeWithText("Create account").performClick()
            onNodeWithText("Create & get API key").assertExists()
        } finally { store.close(); scope.cancel() }
    }

    @Test fun connectionDialogStillAcceptsExistingKeys() = runDesktopComposeUiTest(width = 412, height = 892, testTimeout = 30.seconds) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val credentials = MemoryCredentials()
        val store = ReaderStore(ReaderRepository(demoService(), NoCache), credentials, scope)
        try {
            setContent { ReaderApp(store) }
            onNodeWithText("Named board").performClick()
            onNodeWithText("Connect API key").performClick()
            onNodeWithText("API key").performTextInput("existing-key")
            onNodeWithText("Connect").performClick()
            onNodeWithText("Your API key is connected.").assertIsDisplayed()
            assertEquals("existing-key", credentials.key)
        } finally { store.close(); scope.cancel() }
    }

    @Test fun registrationErrorsKeepAccountDetailsForCorrection() = runDesktopComposeUiTest(width = 412, height = 700, testTimeout = 30.seconds) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = object : FakeService() {
            override suspend fun register(request: RegistrationRequest): RegisteredAccount =
                throw BoardException(ReaderFailure("This account name is already taken. Choose another name."))
        }
        val store = ReaderStore(ReaderRepository(service, NoCache), MemoryCredentials(), scope)
        try {
            setContent { ReaderApp(store) }
            onNodeWithContentDescription("Connection settings").performClick()
            onNodeWithText("Account name").performTextInput("taken-name")
            onNodeWithText("Create & get API key").performClick()
            onNodeWithText("This account name is already taken. Choose another name.").performScrollTo().assertIsDisplayed()
            onNodeWithText("taken-name").performScrollTo().assertIsDisplayed()
            onNodeWithText("Create & get API key").assertIsEnabled()
        } finally { store.close(); scope.cancel() }
    }
}

private fun saveScreenshot(bitmap: androidx.compose.ui.graphics.ImageBitmap, name: String) {
    val directory = File("build/screenshots").apply { mkdirs() }
    Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
        image.encodeToData()!!.use { File(directory, name).writeBytes(it.bytes) }
    }
}
