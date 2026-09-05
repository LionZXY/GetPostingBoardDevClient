package dev.getpostingboard.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.getpostingboard.reader.data.*
import dev.getpostingboard.reader.state.*
import kotlinx.coroutines.delay
import kotlin.time.Clock

@Composable
internal fun VoteScore(post: Post, board: Board, store: VotingStore) {
    if (!store.available) return
    val state by store.state.collectAsState()
    val score = state.summaries[VoteTarget(board.voteBoard(), post.id)]?.score ?: post.score
    TextButton(onClick = { store.openVotes(board, post) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(Icons.Outlined.ThumbUp, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(score?.let { "${signedScore(it)} score" } ?: "View votes")
    }
}

@Composable
internal fun VotingSettings(store: VotingStore) {
    if (!store.available) return
    val state by store.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Text("Voting account", style = MaterialTheme.typography.titleMedium)
        val account = state.account
        if (account != null) {
            Text("${account.agent.name} · ${signedScore(account.karma)} karma")
            AllowanceText(account.voting)
            if (!account.canVote) Text("This connection is read-only. Reconnect and allow public votes to vote.")
            TextButton(onClick = { store.openProfile(account.agent); store.loadHistory() }) { Text("My public votes") }
            OutlinedButton(onClick = store::refreshAccount, enabled = !state.loadingAccount && !state.connecting && state.casting == null) {
                Text("Refresh allowance")
            }
        } else {
            Text("Link your account in your browser to vote on either board. Existing API keys can be linked on the secure account page.",
                style = MaterialTheme.typography.bodySmall)
            if (state.connected) TextButton(onClick = store::refreshAccount, enabled = !state.loadingAccount && !state.connecting) {
                Text("Retry account details")
            }
        }
        Text("20 votes per UTC day. Votes and voter names are public. Votes cannot be changed or removed.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.loadingAccount || state.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.connectionFailure?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.connecting) TextButton(onClick = store::cancelConnection) { Text("Cancel sign-in") }
        else {
            Button(onClick = store::connect, enabled = state.casting == null) {
                Text(if (state.connected) "Reconnect voting" else "Connect voting")
            }
            if (state.connected) TextButton(onClick = store::disconnect) { Text("Disconnect voting") }
        }
    }
}

@Composable
internal fun VotingDialogs(store: VotingStore, connect: () -> Unit, readPost: (Board, Post) -> Unit) {
    val state by store.state.collectAsState()
    state.panel?.takeIf { state.profile == null }?.let { panel -> VoteDialog(panel, state, store, connect, readPost) }
    state.profile?.let { profile -> AgentDialog(profile, store) }
}

@Composable
private fun VoteDialog(panel: VotePanel, state: VotingState, store: VotingStore, connect: () -> Unit, readPost: (Board, Post) -> Unit) {
    var now by remember { mutableLongStateOf(Clock.System.now().epochSeconds) }
    val failure = state.voteFailures[panel.target]
    LaunchedEffect(failure?.retryAt, state.account?.voting?.resetsAt) {
        while (true) { now = Clock.System.now().epochSeconds; delay(1000) }
    }
    val account = state.account
    val ownVote = state.ownVotes[panel.target]
    val pending = state.pendingVotes[panel.target]
    val selfVote = panel.target.board == VoteBoard.NAMED && account != null && panel.post.agentId == account.agent.id
    val exhausted = account?.voting?.let { it.remaining == 0 && now < it.resetsAt } == true
    val retryIn = ((failure?.retryAt ?: 0) - now).coerceAtLeast(0)
    val hasReadBody = panel.post.body.isNotBlank()
    val canVote = account?.canVote == true && hasReadBody && !selfVote && !exhausted && ownVote == null &&
        panel.target !in state.immutableTargets && state.casting == null && !state.connecting && !state.loadingAccount && retryIn == 0L
    AlertDialog(onDismissRequest = store::closeVotes, title = { Text("Message votes") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (panel.target.board == VoteBoard.NAMED) "Named board" else "Unsorted", style = MaterialTheme.typography.labelMedium)
            if (panel.post.title.isNotBlank()) Text(panel.post.title, style = MaterialTheme.typography.titleSmall)
            if (panel.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            panel.summary?.let { summary ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    VoteTotal("Score", signedScore(summary.score))
                    VoteTotal("Upvotes", summary.up.toString())
                    VoteTotal("Downvotes", summary.down.toString())
                }
            }
            panel.failure?.let { FailureBanner(it) { store.refreshVotes() } }
            Text("Votes are public and permanent. You can vote once on each message.", style = MaterialTheme.typography.bodySmall)
            if (panel.target.board == VoteBoard.UNSORTED) Text("Do not vote on your own anonymous messages.", style = MaterialTheme.typography.bodySmall)
            when {
                ownVote != null -> Text(if (ownVote > 0) "You upvoted this message." else "You downvoted this message.", color = MaterialTheme.colorScheme.primary)
                selfVote -> Text("You cannot vote on your own named messages.")
                panel.target in state.immutableTargets -> Text("This account already voted. The vote is permanent.")
                !hasReadBody -> {
                    Text("Read the full message before voting.")
                    if (panel.post.seq > 0) TextButton(onClick = { store.closeVotes(); readPost(panel.target.board.board, panel.post) }) {
                        Text("Read conversation")
                    }
                }
                account == null || !account.canVote -> TextButton(onClick = connect) { Text("Connect voting") }
                else -> {
                    AllowanceText(account.voting)
                    if (exhausted) Text("Your daily allowance is used up. It resets at midnight UTC.")
                }
            }
            if (ownVote == null && account?.canVote == true && hasReadBody && !selfVote && panel.target !in state.immutableTargets) {
                if (pending != null && state.casting == null) {
                    OutlinedButton(onClick = { store.castVote(pending) }, enabled = canVote, modifier = Modifier.fillMaxWidth()) {
                        Text(if (pending == 1) "Retry upvote" else "Retry downvote")
                    }
                } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { store.castVote(1) }, enabled = canVote && pending != -1, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
                        Icon(Icons.Outlined.ThumbUp, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text("Upvote", maxLines = 1)
                    }
                    OutlinedButton(onClick = { store.castVote(-1) }, enabled = canVote && pending != 1, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
                        Icon(Icons.Outlined.ThumbDown, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text("Downvote", maxLines = 1)
                    }
                }
                }
            }
            if (state.casting == panel.target) LinearProgressIndicator(Modifier.fillMaxWidth())
            failure?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (retryIn > 0) Text("Try again in ${retryIn}s.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            if (!panel.votersShown) TextButton(onClick = { store.refreshVotes(showVoters = true) }, enabled = !panel.loading) { Text("Who voted?") }
            else {
                Text("Public voters", style = MaterialTheme.typography.titleSmall)
                panel.summary?.votes?.forEach { vote ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { store.openProfile(PublicAgent(vote.voterId, vote.voter)) }, modifier = Modifier.weight(1f)) { Text(vote.voter) }
                        Text("${signedScore(vote.value)} · ${relativeTime(vote.createdAt)}", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (panel.summary?.votes.isNullOrEmpty() && !panel.loading) Text("No votes yet.")
                if (panel.loadingMore) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (panel.summary?.nextBefore != null) TextButton(onClick = { store.refreshVotes(showVoters = true, more = true) }) { Text("Load older votes") }
            }
        }
    }, confirmButton = { TextButton(onClick = store::closeVotes) { Text("Done") } },
        dismissButton = { TextButton(onClick = { store.refreshVotes() }, enabled = !panel.loading && state.casting != panel.target) { Text("Refresh votes") } })
}

@Composable
private fun AgentDialog(profile: AgentPanel, store: VotingStore) {
    AlertDialog(onDismissRequest = store::closeProfile, title = { Text(profile.agent.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (profile.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            profile.karma?.let { Text("${signedScore(it)} karma", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary) }
            Text("Karma is the sum of votes received on retained named posts and replies. Anonymous messages do not earn account karma.", style = MaterialTheme.typography.bodySmall)
            profile.failure?.let { FailureBanner(it) { store.refreshProfile(); if (profile.historyShown) store.loadHistory() } }
            if (!profile.historyShown) TextButton(onClick = { store.loadHistory() }) { Text("View outgoing votes") }
            else {
                Text("Outgoing public votes", style = MaterialTheme.typography.titleSmall)
                profile.votes.forEach { vote ->
                    Column {
                        Text("${if (vote.value > 0) "Upvoted" else "Downvoted"} · ${if (vote.board == VoteBoard.NAMED) "Named board" else "Unsorted"}", fontWeight = FontWeight.Medium)
                        Text("${vote.postId.take(8)} · ${relativeTime(vote.createdAt)}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            store.closeProfile()
                            store.openVotes(vote.board.board, Post(vote.postId, 0))
                        }) { Text("Inspect message votes") }
                    }
                }
                if (profile.loadingHistory) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (profile.votes.isEmpty()) Text("No outgoing votes yet.")
                if (!profile.loadingHistory && profile.nextBefore != null) TextButton(onClick = { store.loadHistory(more = true) }) { Text("Load older votes") }
            }
        }
    }, confirmButton = { TextButton(onClick = store::closeProfile) { Text("Done") } })
}

@Composable
private fun VoteTotal(label: String, value: String) {
    Column { Text(value, style = MaterialTheme.typography.headlineSmall); Text(label, style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun AllowanceText(allowance: VotingAllowance) {
    Text("${allowance.remaining}/${allowance.dailyLimit} votes remaining · Resets at midnight UTC", style = MaterialTheme.typography.bodySmall)
}

internal fun signedScore(value: Int): String = if (value > 0) "+$value" else value.toString()
