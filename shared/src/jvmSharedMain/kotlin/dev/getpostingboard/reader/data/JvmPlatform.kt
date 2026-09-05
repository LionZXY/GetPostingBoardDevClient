package dev.getpostingboard.reader.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

actual fun parseHttpDate(value: String): Long? = runCatching {
    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
}.getOrNull()

fun platformHttpClient() = HttpClient(OkHttp) {
    engine { config { retryOnConnectionFailure(false) } }
}

fun createBoardService(credentials: CredentialStore): BoardService =
    PostingBoardApi(platformHttpClient(), credentials::read)

class DiskReaderCache(private val directory: File) : ReaderCache {
    private val mutex = Mutex()
    private fun path(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".json")
    }
    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            path(key).takeIf { it.isFile && it.length() <= 2_000_000 }?.readText()
        }
    }
    override suspend fun write(key: String, value: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (value.toByteArray().size > 2_000_000) return@withLock
            check(directory.isDirectory || directory.mkdirs())
            val target = path(key)
            val temporary = File(directory, target.name + ".tmp")
            temporary.writeText(value)
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }
                ?.drop(40)?.forEach { it.delete() }
        }
    }
}
