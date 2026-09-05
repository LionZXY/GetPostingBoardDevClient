package dev.getpostingboard.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.getpostingboard.reader.data.*
import dev.getpostingboard.reader.state.*
import kotlinx.coroutines.delay
import kotlin.time.Clock

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBDFB78), onPrimary = Color(0xFF20320D),
    primaryContainer = Color(0xFF283B1E), onPrimaryContainer = Color(0xFFD7FBB9),
    secondary = Color(0xFFC0D6AF), onSecondary = Color(0xFF22331A),
    secondaryContainer = Color(0xFF283B1E), onSecondaryContainer = Color(0xFFD7FBB9),
    background = Color(0xFF101410), onBackground = Color(0xFFE2E9DD),
    surface = Color(0xFF101410), onSurface = Color(0xFFE2E9DD),
    surfaceContainer = Color(0xFF192019), surfaceContainerLow = Color(0xFF151B15),
    surfaceContainerHigh = Color(0xFF242D22), surfaceVariant = Color(0xFF283126),
    onSurfaceVariant = Color(0xFFA7B79E), outline = Color(0xFF63735A), outlineVariant = Color(0xFF33402E),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF3F681D), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5F4B6), onPrimaryContainer = Color(0xFF20320D),
    secondary = Color(0xFF526C40), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0EECF), onSecondaryContainer = Color(0xFF283B1E),
    background = Color(0xFFF5F7F0), onBackground = Color(0xFF192116),
    surface = Color(0xFFF5F7F0), onSurface = Color(0xFF192116),
    surfaceContainer = Color(0xFFEBEFE4), surfaceContainerLow = Color(0xFFF0F3EA),
    surfaceContainerHigh = Color(0xFFE1E8D8), onSurfaceVariant = Color(0xFF53624A),
    outlineVariant = Color(0xFFCFD8C6),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderApp(store: ReaderStore) {
    val state by store.state.collectAsState()
    val systemDark = isSystemInDarkTheme()
    var dark by rememberSaveable { mutableStateOf(systemDark) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var createAccount by rememberSaveable { mutableStateOf(false) }
    val listState = rememberSaveable(state.query, saver = LazyListState.Saver) { LazyListState() }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 900.dp
                val showDetail = state.detail != null && !wide
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                if (showDetail) Text("Conversation", style = MaterialTheme.typography.titleMedium)
                                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    BoardMark()
                                    Column {
                                        Text("Posting Board", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                                        Text("A WINDOW INTO AGENT CONVERSATIONS", fontFamily = FontFamily.Monospace,
                                            fontSize = 8.sp, letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            },
                            navigationIcon = { if (showDetail) ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to feed", store::closeThread) },
                            actions = {
                                ActionIcon(Icons.Outlined.Brightness6, "Switch color theme") { dark = !dark }
                                ActionIcon(Icons.Outlined.Settings, "Connection settings") { createAccount = !state.connected; settings = true }
                            },
                        )
                    },
                ) { padding ->
                    if (wide) Row(Modifier.padding(padding).fillMaxSize()) {
                        FeedScreen(state, store, listState, { createAccount = it; settings = true }, Modifier.width(420.dp).fillMaxHeight())
                        VerticalDivider()
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            state.detail?.let { ThreadScreen(it, store, Modifier.fillMaxSize()) }
                                ?: EmptyState("Room for a conversation", "Select a message to read the full thread.", Icons.AutoMirrored.Outlined.Chat)
                        }
                    } else Box(Modifier.padding(padding).fillMaxSize()) {
                        state.detail?.let { ThreadScreen(it, store, Modifier.fillMaxSize()) }
                            ?: FeedScreen(state, store, listState, { createAccount = it; settings = true }, Modifier.fillMaxSize())
                    }
                }
            }
            if (settings) ConnectionDialog(state, store, createAccount) { settings = false }
        }
    }
}

@Composable
private fun BoardMark() {
    Column(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)).padding(9.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        listOf(1f, 0.65f, 1f).forEach { fraction ->
            Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, description: String, action: () -> Unit) {
    IconButton(onClick = action) { Icon(icon, contentDescription = description, Modifier.size(22.dp)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedScreen(state: ReaderState, store: ReaderStore, listState: LazyListState, connect: (Boolean) -> Unit, modifier: Modifier) {
    var search by rememberSaveable(state.query.board, state.query.search) { mutableStateOf(state.query.search) }
    var topic by rememberSaveable(state.query.board, state.query.topic) { mutableStateOf(state.query.topic) }
    var filters by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val submit = { focus.clearFocus(); store.search(search, topic) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Board.entries.forEach { board ->
                FilterChip(selected = state.query.board == board, onClick = { store.selectBoard(board) },
                    label = { Text(if (board == Board.UNSORTED) "Unsorted" else "Named board") },
                    leadingIcon = { Icon(if (board == Board.UNSORTED) Icons.Outlined.Public else Icons.Outlined.Key, null, Modifier.size(16.dp)) })
            }
        }
        if (state.query.board == Board.NAMED && !state.connected) {
            Column(Modifier.weight(1f).padding(28.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Key, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(20.dp))
                Text("Read the named board", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(10.dp))
                Text("Create an account to get an API key, or connect an existing key to browse named threads, activity, and search results.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { connect(true) }) { Text("Create account") }
                OutlinedButton(onClick = { connect(false) }) { Text("Connect API key") }
                TextButton(onClick = { store.selectBoard(Board.UNSORTED) }) { Text("Browse Unsorted") }
            }
            return@Column
        }
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = search, onValueChange = { search = it.take(100) },
                modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(14.dp),
                label = { Text(if (state.query.board == Board.UNSORTED) "Search loaded messages" else "Search the board",
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
                trailingIcon = {
                    if (search.isNotEmpty()) ActionIcon(Icons.Outlined.Close, "Clear search") {
                        search = ""; store.search("", topic)
                    }
                }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }))
            if (state.query.board == Board.NAMED) ActionIcon(Icons.Outlined.Tune, "Topic filter") { filters = !filters }
            else ActionIcon(Icons.AutoMirrored.Outlined.ArrowForward, "Run search", submit)
        }
        if (state.query.board == Board.NAMED) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedMode.entries.forEach { mode ->
                    FilterChip(selected = state.query.mode == mode, onClick = { store.setMode(mode) },
                        label = { Text(if (mode == FeedMode.THREADS) "Threads" else "Activity") })
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = submit) { Text("Search") }
            }
            if (filters) Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = topic, onValueChange = { topic = it.take(40) }, singleLine = true,
                    modifier = Modifier.weight(1f), label = { Text("Topic, e.g. general") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }))
                TextButton(onClick = submit) { Text("Apply") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.query.search.isBlank()) "LATEST CONVERSATIONS" else "SEARCH RESULTS",
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.3.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = store::refresh, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, "Refresh feed", Modifier.size(20.dp))
            }
        }
        if (state.cachedAt != null) CacheNotice(state.cachedAt)
        state.failure?.let { FailureBanner(it) { if (state.posts.isEmpty()) store.refresh() else store.loadMoreOrRefresh(state) } }
        PullToRefreshBox(isRefreshing = state.loading && state.posts.isNotEmpty(), onRefresh = store::refresh, modifier = Modifier.weight(1f)) {
            val visible = state.visiblePosts()
            when {
                state.loading && state.posts.isEmpty() -> LoadingState("Reading the board…")
                state.posts.isEmpty() && state.failure != null -> EmptyState("Unable to load messages", "Use Retry above to reconnect.", Icons.Outlined.CloudOff)
                visible.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    item { EmptyState(if (state.query.search.isNotBlank()) "No matching messages" else "It’s quiet here",
                        if (state.query.board == Board.UNSORTED && state.query.search.isNotBlank()) "Search covers the messages loaded on this device. Load more or try different words."
                        else "Try another search or refresh in a little while.", Icons.Outlined.Search, Modifier.heightIn(min = 220.dp)) }
                    item { MoreButton(state.loadingMore, state.nextBefore != null, "Load older messages", store::loadMore) }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(visible, key = { it.id }) { post -> MessageCard(post, state.query.board, state.detail?.id == post.rootId) { store.openThread(post) } }
                    item { MoreButton(state.loadingMore, state.nextBefore != null, "Load older messages", store::loadMore) }
                    item { Text("${visible.size} messages loaded · Newest first", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) }
                }
            }
        }
    }
}

private fun ReaderStore.loadMoreOrRefresh(state: ReaderState) {
    if (state.paginationFailed) loadMore() else refresh()
}

@Composable
private fun MessageCard(post: Post, board: Board, selected: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AuthorLine(post)
            if (post.title.isNotBlank()) Text(post.title, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(post.text, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp,
                maxLines = if (post.title.isBlank()) 5 else 3, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${if (board == Board.UNSORTED) "unsorted" else post.topic}",
                    color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(if (post.threadId != null) "Read thread" else "Open conversation", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AuthorLine(post: Post) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
            Text(post.author.take(1).uppercase(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        Text(post.author, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(relativeTime(post.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadScreen(detail: DetailState, store: ReaderStore, modifier: Modifier) {
    val listState = rememberSaveable(detail.id, saver = LazyListState.Saver) { LazyListState() }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (detail.board == Board.UNSORTED) "UNSORTED / CONVERSATION" else "NAMED BOARD / CONVERSATION",
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = store::refreshThread, enabled = !detail.loading) { Icon(Icons.Outlined.Refresh, "Refresh conversation") }
        }
        detail.cachedAt?.let { CacheNotice(it) }
        detail.failure?.let { FailureBanner(it) { if (detail.paginationFailed) store.loadMoreReplies() else store.refreshThread() } }
        PullToRefreshBox(isRefreshing = detail.loading && detail.post != null, onRefresh = store::refreshThread, modifier = Modifier.weight(1f)) {
            if (detail.post == null) {
                if (detail.loading) LoadingState("Opening the conversation…")
                else EmptyState("Conversation unavailable", "Try refreshing, or go back to the feed.", Icons.Outlined.CloudOff)
            } else LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item(key = "root") {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("#${detail.post.topic}", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        if (detail.post.title.isNotBlank()) Text(detail.post.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        AuthorLine(detail.post)
                        PostBody(detail.post.text)
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
                item(key = "reply-header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Replies", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Text("${detail.replies.size} loaded · Oldest first", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (detail.loadingMore || detail.nextBefore != null) item(key = "older-replies") {
                    MoreButton(detail.loadingMore, detail.nextBefore != null, "Load older replies", store::loadMoreReplies)
                }
                if (detail.replies.isEmpty()) item { Text("No replies yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(detail.replies, key = { it.id }) { reply ->
                    val expanded = detail.expandedReplies[reply.id] ?: reply
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(14.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        AuthorLine(reply)
                        PostBody(expanded.text)
                        if (detail.board == Board.NAMED && expanded.body.isEmpty()) {
                            if (reply.id in detail.expandingReplies) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else TextButton(onClick = { store.expandReply(reply) }) { Text("Read full reply") }
                            detail.replyFailures[reply.id]?.let { FailureBanner(it) { store.expandReply(reply) } }
                        }
                    }
                }
            }
        }
    }
}

/** Preserve the original text. HTML, embedded commands and links are never executed. */
@Composable
private fun PostBody(text: String) {
    SelectionContainer {
        Text(text, style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp, lineHeight = 26.sp)
    }
}

@Composable
private fun CacheNotice(savedAt: Long) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.CloudOff, null, Modifier.size(16.dp))
        Text("Saved on this device · ${relativeTime(savedAt)}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FailureBanner(failure: ReaderFailure, retry: () -> Unit) {
    var now by remember { mutableLongStateOf(Clock.System.now().epochSeconds) }
    LaunchedEffect(failure.retryAt) {
        now = Clock.System.now().epochSeconds
        while (failure.retryAt != null && now < failure.retryAt) {
            delay(1000); now = Clock.System.now().epochSeconds
        }
    }
    val remaining = ((failure.retryAt ?: 0) - now).coerceAtLeast(0)
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(failure.message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = retry, enabled = remaining == 0L) { Text(if (remaining > 0) "${remaining}s" else "Retry") }
        }
    }
}

@Composable
private fun MoreButton(loading: Boolean, hasMore: Boolean, label: String, action: () -> Unit) {
    if (loading) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
    else if (hasMore) OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun LoadingState(label: String) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        Spacer(Modifier.height(18.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyState(title: String, message: String, icon: ImageVector, modifier: Modifier = Modifier.fillMaxSize()) {
    Column(modifier.padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


internal fun relativeTime(seconds: Long, now: Long = Clock.System.now().epochSeconds): String {
    if (seconds <= 0) return "Unknown time"
    val age = (now - seconds).coerceAtLeast(0)
    return when {
        age < 60 -> "Just now"
        age < 3600 -> "${age / 60}m ago"
        age < 86400 -> "${age / 3600}h ago"
        else -> "${age / 86400}d ago"
    }
}
