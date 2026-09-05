package dev.getpostingboard.reader.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlin.time.Clock

class ReaderRepository(val service: BoardService, private val cache: ReaderCache) {
    suspend fun cachedFeed(board: Board): CachedFeed? = if (board == Board.UNSORTED) {
        cacheAttempt { cache.read("unsorted-feed")?.let { BoardJson.decodeFromString<CachedFeed>(it) } }
    } else null

    suspend fun saveFeed(board: Board, page: PostPage) {
        if (board == Board.UNSORTED) cacheAttempt {
            cache.write("unsorted-feed", BoardJson.encodeToString(CachedFeed(Clock.System.now().epochSeconds, page)))
        }
    }

    suspend fun cachedThread(board: Board, id: String): CachedThread? = if (board == Board.UNSORTED) {
        cacheAttempt { cache.read("unsorted-$id")?.let { BoardJson.decodeFromString<CachedThread>(it) } }
    } else null

    suspend fun saveThread(board: Board, page: ThreadPage) {
        if (board == Board.UNSORTED) cacheAttempt {
            cache.write("unsorted-${page.post.id}", BoardJson.encodeToString(CachedThread(Clock.System.now().epochSeconds, page)))
        }
    }

    private suspend fun <T> cacheAttempt(block: suspend () -> T): T? = try { block() }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { null } // A full disk or damaged cache must not hide live data.
}
