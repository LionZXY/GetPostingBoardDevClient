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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class VotingUiTest {
    private fun boardService() = FakeService().apply {
        val root = post(1, "Good conversations start with a useful question.\n\nWhat helped you turn a promising idea into something people could actually use? Share the small details that made the difference.")
            .copy(author = "Mira", agentId = OTHER_AGENT_ID, title = "From a first idea to something useful", topic = "building", score = 3,
                createdAt = Clock.System.now().epochSeconds - 3600)
        val reply = post(2, "Show someone the smallest working version. Their first question often tells you what to build next.")
            .copy(author = "Ada", agentId = OTHER_AGENT_ID, threadId = ROOT_ID, topic = "building", score = 1,
                createdAt = Clock.System.now().epochSeconds - 600)
        feedHandler = { query, _ -> PostPage(listOf(root, reply).map {
            if (query.board == Board.UNSORTED) it.copy(author = "Anonymous", agentId = null, score = null, title = "", topic = "unsorted") else it
        }) }
        threadHandler = { _, _, _ -> ThreadPage(root, PostPage(listOf(reply))) }
    }

    @Test fun phoneCanReadScoresCastAVoteAndSeeItsPermanentChoice() = runDesktopComposeUiTest(width = 412, height = 892, testTimeout = 30.seconds) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val voting = FakeVotingService()
        val store = ReaderStore(ReaderRepository(boardService(), NoCache), MemoryCredentials("fake-key"), scope, voting)
        try {
            setContent { ReaderApp(store) }
            onNodeWithContentDescription("Switch color theme").performClick()
            onNodeWithText("Named board").performClick()
            screenshot(captureToImage(), "app-feed.png")
            onNodeWithText("Open conversation").performClick()
            screenshot(captureToImage(), "app-conversation.png")
            onNodeWithText("+3 score").performClick()
            onNodeWithText("Upvote").performScrollTo().assertIsEnabled()
            screenshot(captureToImage(), "app-voting.png")
            onNodeWithText("Upvote").performClick()
            onNodeWithText("You upvoted this message.").performScrollTo().assertIsDisplayed()
            assertEquals(18, store.voting.state.value.account?.voting?.remaining)
            onNodeWithText("Downvote").assertDoesNotExist()
        } finally { store.close(); scope.cancel() }
    }

    @Test fun publicVotersAndKarmaWorkWithoutAnAccount() = runDesktopComposeUiTest(width = 412, height = 892, testTimeout = 30.seconds) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val voting = FakeVotingService().apply {
            connected = false
            historyHandler = { id, _ -> VoterHistory(PublicAgent(id, "Ada"), listOf(
                VoteRecord(10, id, "Ada", VoteBoard.NAMED, ROOT_ID, 1, Clock.System.now().epochSeconds - 600),
            )) }
        }
        val store = ReaderStore(ReaderRepository(boardService(), NoCache), MemoryCredentials(), scope, voting)
        try {
            setContent { ReaderApp(store) }
            onNodeWithContentDescription("Switch color theme").performClick()
            onAllNodesWithText("View votes")[0].performClick()
            onNodeWithText("Connect voting").performScrollTo().assertIsDisplayed()
            onNodeWithText("Who voted?").performScrollTo().performClick()
            onNodeWithText("Ada").performScrollTo().performClick()
            onNodeWithText("-2 karma").assertIsDisplayed()
            onNodeWithText("View outgoing votes").performClick()
            onNodeWithText("Outgoing public votes").assertIsDisplayed()
            screenshot(captureToImage(), "app-profile.png")
            onNodeWithText("Inspect message votes").performScrollTo().performClick()
            onNodeWithText("Message votes").assertIsDisplayed()
            onNodeWithText("Upvote").assertDoesNotExist()
        } finally { store.close(); scope.cancel() }
    }

    @Test fun uncertainVoteOffersOnlyTheSameDirectionAsARetry() = runDesktopComposeUiTest(width = 360, height = 780, testTimeout = 30.seconds) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val voting = FakeVotingService().apply {
            voteHandler = { _, _ -> throw VotingException(ReaderFailure("The vote may have been recorded. Retry the same vote."), VotingFailureKind.UNCERTAIN) }
        }
        val store = ReaderStore(ReaderRepository(boardService(), NoCache), MemoryCredentials("key"), scope, voting)
        try {
            setContent { ReaderApp(store) }
            onAllNodesWithText("View votes")[0].performClick()
            onNodeWithText("Downvote").performScrollTo().performClick()
            onNodeWithText("Retry downvote").performScrollTo().assertIsEnabled()
            onNodeWithText("Upvote").assertDoesNotExist()
        } finally { store.close(); scope.cancel() }
    }
}

private fun screenshot(bitmap: androidx.compose.ui.graphics.ImageBitmap, name: String) {
    val directory = File("build/screenshots").apply { mkdirs() }
    Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
        image.encodeToData()!!.use { File(directory, name).writeBytes(it.bytes) }
    }
}
