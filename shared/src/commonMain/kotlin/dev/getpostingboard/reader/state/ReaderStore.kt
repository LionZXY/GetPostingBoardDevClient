package dev.getpostingboard.reader.state

import dev.getpostingboard.reader.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DetailState(
    val board: Board,
    val id: String,
    val post: Post? = null,
    val replies: List<Post> = emptyList(),
    val nextBefore: Long? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val paginationFailed: Boolean = false,
    val failure: ReaderFailure? = null,
    val cachedAt: Long? = null,
    val expandedReplies: Map<String, Post> = emptyMap(),
    val expandingReplies: Set<String> = emptySet(),
    val replyFailures: Map<String, ReaderFailure> = emptyMap(),
)

data class ReaderState(
    val query: FeedQuery = FeedQuery(),
    val posts: List<Post> = emptyList(),
    val nextBefore: Long? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val paginationFailed: Boolean = false,
    val failure: ReaderFailure? = null,
    val cachedAt: Long? = null,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val connectionFailure: ReaderFailure? = null,
    val registeredName: String? = null,
    val keyNeedsSaving: Boolean = false,
    val registrationUncertain: Boolean = false,
    val detail: DetailState? = null,
)

/** Own this store in an Android ViewModel (or a desktop composition scope). */
class ReaderStore(
    private val repository: ReaderRepository,
    private val credentials: CredentialStore,
    private val scope: CoroutineScope,
    votingService: VotingService? = null,
    oauthBrowser: OAuthBrowser? = null,
) {
    val voting = VotingStore(votingService, scope, oauthBrowser)
    private val mutable = MutableStateFlow(ReaderState(connected = !credentials.read().isNullOrBlank()))
    val state = mutable.asStateFlow()
    private var feedJob: Job? = null
    private var detailJob: Job? = null
    private var connectionJob: Job? = null
    private var connectionVersion = 0
    // Keep an issued key in memory if secure storage fails; retry storage, never registration.
    private var pendingAccount: RegisteredAccount? = null
    private val replyJobs = mutableMapOf<String, Job>()
    private var feedVersion = 0
    private var detailVersion = 0

    init { refresh() }

    fun selectBoard(board: Board) {
        if (board == mutable.value.query.board) return
        changeQuery(FeedQuery(board = board))
    }

    fun setMode(mode: FeedMode) = changeQuery(mutable.value.query.copy(mode = mode))

    fun search(text: String, topic: String) {
        val search = text.trim()
        val slug = topic.trim().lowercase()
        if (search.length > 100 || search.split(Regex("\\s+")).count { it.isNotBlank() } > 12) {
            mutable.update { it.copy(failure = ReaderFailure("Use at most 100 characters and 12 words in a search.")) }
            return
        }
        if (slug.isNotEmpty() && !Regex("[a-z0-9][a-z0-9-]{0,39}").matches(slug)) {
            mutable.update { it.copy(failure = ReaderFailure("Topics use up to 40 lowercase letters, numbers, and hyphens.")) }
            return
        }
        // Unsorted has no server-side search; keep all loaded messages and filter in the UI.
        if (mutable.value.query.board == Board.UNSORTED) {
            mutable.update { it.copy(query = it.query.copy(search = search), failure = null) }
        } else changeQuery(mutable.value.query.copy(search = search, topic = slug))
    }

    private fun changeQuery(query: FeedQuery) {
        closeThread()
        feedJob?.cancel()
        mutable.update { it.copy(query = query, posts = emptyList(), nextBefore = null, failure = null, cachedAt = null) }
        refresh()
    }

    fun refresh() {
        feedJob?.cancel()
        val version = ++feedVersion
        val query = mutable.value.query
        if (query.board == Board.NAMED && !mutable.value.connected) {
            mutable.update { it.copy(loading = false, loadingMore = false, failure = null) }
            return
        }
        mutable.update { it.copy(loading = true, loadingMore = false, paginationFailed = false, failure = null) }
        feedJob = scope.launch {
            if (mutable.value.posts.isEmpty()) {
                repository.cachedFeed(query.board)?.let { cached ->
                    if (version == feedVersion) mutable.update {
                        it.copy(posts = cached.page.items, nextBefore = cached.page.nextBefore, cachedAt = cached.savedAt)
                    }
                }
            }
            try {
                val page = repository.service.feed(query)
                if (version != feedVersion) return@launch
                val unique = page.copy(items = page.items.distinctBy { it.id })
                mutable.update { it.copy(posts = unique.items, nextBefore = unique.nextBefore, loading = false, cachedAt = null) }
                repository.saveFeed(query.board, unique)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (version == feedVersion) mutable.update { it.copy(loading = false, failure = e.asFailure()) }
            }
        }
    }

    fun loadMore() {
        val snapshot = mutable.value
        val before = snapshot.nextBefore ?: return
        if (snapshot.loading || snapshot.loadingMore) return
        val version = feedVersion
        mutable.update { it.copy(loadingMore = true, failure = null) }
        feedJob = scope.launch {
            try {
                val page = repository.service.feed(snapshot.query, before)
                if (version != feedVersion) return@launch
                // Never spin forever if the service repeats a pagination cursor.
                val next = page.nextBefore?.takeIf { it < before && page.items.isNotEmpty() }
                val posts = (mutable.value.posts + page.items).distinctBy { it.id }
                mutable.update { it.copy(posts = posts, nextBefore = next, loadingMore = false) }
                repository.saveFeed(snapshot.query.board, PostPage(posts, next))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (version == feedVersion) mutable.update { it.copy(loadingMore = false, paginationFailed = true, failure = e.asFailure()) }
            }
        }
    }

    fun openThread(post: Post) {
        closeThread()
        mutable.update { it.copy(detail = DetailState(it.query.board, post.rootId)) }
        refreshThread()
    }

    fun refreshThread() {
        val detail = mutable.value.detail ?: return
        detailJob?.cancel()
        replyJobs.values.forEach { it.cancel() }
        replyJobs.clear()
        val version = ++detailVersion
        updateDetail { it.copy(loading = true, loadingMore = false, paginationFailed = false, failure = null, expandingReplies = emptySet()) }
        detailJob = scope.launch {
            if (detail.post == null) repository.cachedThread(detail.board, detail.id)?.let { cached ->
                if (version == detailVersion) updateDetail {
                    it.copy(post = cached.page.post, replies = cached.page.replies.items.sortedBy { p -> p.seq },
                        nextBefore = cached.page.replies.nextBefore, cachedAt = cached.savedAt)
                }
            }
            try {
                val page = repository.service.thread(detail.board, detail.id)
                if (version != detailVersion) return@launch
                updateDetail { it.copy(post = page.post, replies = page.replies.items.distinctBy { p -> p.id }.sortedBy { p -> p.seq },
                    nextBefore = page.replies.nextBefore, loading = false, cachedAt = null, expandedReplies = emptyMap(), replyFailures = emptyMap()) }
                repository.saveThread(detail.board, page)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (version == detailVersion) updateDetail { it.copy(loading = false, failure = e.asFailure()) } }
        }
    }

    fun loadMoreReplies() {
        val detail = mutable.value.detail ?: return
        val before = detail.nextBefore ?: return
        if (detail.loading || detail.loadingMore) return
        val version = detailVersion
        updateDetail { it.copy(loadingMore = true, failure = null) }
        detailJob = scope.launch {
            try {
                val page = repository.service.thread(detail.board, detail.id, before)
                if (version != detailVersion) return@launch
                val replies = (mutable.value.detail!!.replies + page.replies.items).distinctBy { it.id }.sortedBy { it.seq }
                val next = page.replies.nextBefore?.takeIf { it < before && page.replies.items.isNotEmpty() }
                updateDetail { it.copy(replies = replies, nextBefore = next, loadingMore = false) }
                repository.saveThread(detail.board, ThreadPage(page.post, PostPage(replies, next)))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (version == detailVersion) updateDetail { it.copy(loadingMore = false, paginationFailed = true, failure = e.asFailure()) } }
        }
    }

    fun expandReply(reply: Post) {
        val detail = mutable.value.detail ?: return
        if (reply.id in detail.expandingReplies || reply.id in detail.expandedReplies) return
        val version = detailVersion
        updateDetail { it.copy(expandingReplies = it.expandingReplies + reply.id, replyFailures = it.replyFailures - reply.id) }
        replyJobs[reply.id] = scope.launch {
            try {
                val full = repository.service.fullPost(reply.id)
                if (version == detailVersion) updateDetail {
                    it.copy(expandedReplies = it.expandedReplies + (reply.id to full), expandingReplies = it.expandingReplies - reply.id)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (version == detailVersion) updateDetail {
                    it.copy(expandingReplies = it.expandingReplies - reply.id, replyFailures = it.replyFailures + (reply.id to e.asFailure()))
                }
            }
        }
    }

    fun closeThread() {
        ++detailVersion
        detailJob?.cancel()
        replyJobs.values.forEach { it.cancel() }
        replyJobs.clear()
        mutable.update { it.copy(detail = null) }
    }

    fun connect(rawKey: String) {
        if (mutable.value.connecting || mutable.value.keyNeedsSaving) return
        val key = rawKey.trim()
        if (key.isEmpty() || key.any { it.isWhitespace() || it.isISOControl() }) {
            mutable.update { it.copy(connectionFailure = ReaderFailure("Enter a valid API key without spaces.")) }
            return
        }
        val version = ++connectionVersion
        mutable.update { it.copy(connecting = true, connectionFailure = null) }
        connectionJob = scope.launch {
            try {
                repository.service.validateKey(key)
                if (version != connectionVersion) return@launch
                credentials.write(key)
                mutable.update { it.copy(connected = true, connecting = false, registeredName = null) }
                changeQuery(FeedQuery(board = Board.NAMED))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (version == connectionVersion) mutable.update { it.copy(connecting = false, connectionFailure = e.asFailure()) }
            }
        }
    }

    fun register(rawName: String, rawDescription: String) {
        val snapshot = mutable.value
        if (snapshot.connecting || snapshot.connected || snapshot.keyNeedsSaving || snapshot.registrationUncertain) return
        val name = rawName.trim()
        val description = rawDescription.trim()
        registrationFailure(name, description)?.let { failure ->
            mutable.update { it.copy(connectionFailure = failure) }
            return
        }
        val version = ++connectionVersion
        mutable.update { it.copy(connecting = true, connectionFailure = null) }
        connectionJob = scope.launch {
            try {
                val account = repository.service.register(RegistrationRequest(name, description))
                if (version != connectionVersion) return@launch
                pendingAccount = account
                mutable.update { it.copy(registeredName = account.name, keyNeedsSaving = true) }
                saveRegisteredKey()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (version == connectionVersion) mutable.update {
                    it.copy(connecting = false, connectionFailure = if (e is RegistrationOutcomeUnknownException)
                        ReaderFailure(e.message.orEmpty()) else e.asFailure(),
                        registrationUncertain = e is RegistrationOutcomeUnknownException)
                }
            }
        }
    }

    fun saveRegisteredKey() {
        val account = pendingAccount ?: return
        try {
            credentials.write(account.apiKey)
        } catch (_: Exception) {
            mutable.update { it.copy(connecting = false, connectionFailure = ReaderFailure(
                "Your account was created, but its key could not be saved on this device. Copy the key now or retry saving it.",
            )) }
            return
        }
        pendingAccount = null
        mutable.update { it.copy(connected = true, connecting = false, keyNeedsSaving = false, connectionFailure = null) }
        changeQuery(FeedQuery(board = Board.NAMED))
    }

    fun availableApiKey(): String? = pendingAccount?.apiKey ?: credentials.read()

    val persistsCredentials: Boolean get() = credentials.persistent

    fun clearConnectionFailure() {
        if (!mutable.value.connecting && !mutable.value.keyNeedsSaving && !mutable.value.registrationUncertain) {
            mutable.update { it.copy(connectionFailure = null) }
        }
    }

    fun disconnect() {
        ++connectionVersion
        connectionJob?.cancel()
        try {
            credentials.write(null)
            pendingAccount = null
            mutable.update { it.copy(connected = false, connecting = false, connectionFailure = null,
                registeredName = null, keyNeedsSaving = false) }
            changeQuery(FeedQuery())
        } catch (_: Exception) {
            mutable.update { it.copy(connectionFailure = ReaderFailure("The saved key could not be removed. Please try again.")) }
        }
    }

    private fun updateDetail(transform: (DetailState) -> DetailState) {
        mutable.update { it.copy(detail = it.detail?.let(transform)) }
    }

    fun close() {
        voting.close()
        ++connectionVersion
        pendingAccount = null
        feedJob?.cancel(); detailJob?.cancel(); connectionJob?.cancel()
        replyJobs.values.forEach { it.cancel() }
        repository.service.close()
    }
}

private fun Exception.asFailure(): ReaderFailure = when (this) {
    is BoardException -> failure
    else -> ReaderFailure("Couldn’t reach Posting Board. Check your connection and try again.")
}

fun ReaderState.visiblePosts(): List<Post> = if (query.board == Board.UNSORTED && query.search.isNotBlank()) {
    posts.filter { query.search.split(Regex("\\s+")).all { word -> it.text.contains(word, ignoreCase = true) } }
} else posts
