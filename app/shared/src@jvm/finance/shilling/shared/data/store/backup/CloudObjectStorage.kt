@file:OptIn(org.mobilenativefoundation.store.core5.ExperimentalStoreApi::class)
package finance.shilling.shared.data.store.backup

import finance.shilling.shared.data.store.readLocalSourceOfTruth
import kotlinx.coroutines.flow.flow
import org.mobilenativefoundation.store.store5.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/** Worker-only object storage, always containing ciphertext. No URL or credential reaches a device. */
interface CloudObjectStorage {
    fun read(path: String): ByteArray?
    fun readRange(path: String, offset: Int, length: Int): ByteArray = requireNotNull(read(path)).let { it.copyOfRange(offset, minOf(it.size, offset + length)) }
    fun write(path: String, bytes: ByteArray)
    fun delete(path: String)
    fun checkReady() = Unit
}

class SupabaseObjectStorage(url: String, private val apiKey: String, private val bucket: String = "cloud-peer") : CloudObjectStorage {
    private val endpoint = url.trimEnd('/') + "/storage/v1"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    init {
        val uri = URI(url)
        require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in listOf("localhost", "127.0.0.1")))
        require(apiKey.isNotBlank() && bucket.matches(Regex("[a-z0-9-]{1,63}")))
    }
    private fun request(path: String) = HttpRequest.newBuilder(URI(endpoint + path)).timeout(Duration.ofSeconds(25))
        .header("apikey", apiKey).apply { if (!apiKey.startsWith("sb_secret_")) header("Authorization", "Bearer $apiKey") }
    private fun safe(path: String): String { require(path.matches(Regex("[a-f0-9]{64}/[A-Za-z0-9-]{16,64}"))); return path }
    override fun read(path: String): ByteArray? {
        val response = http.send(request("/object/authenticated/$bucket/${safe(path)}").GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        return response.body().use { body ->
            if (response.statusCode() == 404) return@use null
            check(response.statusCode() == 200) { "Private cloud object read failed" }
            body.readNBytes(112 * 1024 * 1024 + 1).also { require(it.size <= 112 * 1024 * 1024) }
        }
    }
    override fun readRange(path: String, offset: Int, length: Int): ByteArray {
        require(offset >= 0 && length in 1..1024 * 1024)
        val response = http.send(request("/object/authenticated/$bucket/${safe(path)}")
            .header("Range", "bytes=$offset-${offset + length - 1}").GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        return response.body().use { body ->
            check(response.statusCode() == 206 || (offset == 0 && response.statusCode() == 200)) { "Cloud object range read failed" }
            body.readNBytes(length + 1).also { require(it.size in 1..length) }
        }
    }
    override fun write(path: String, bytes: ByteArray) {
        val response = http.send(request("/object/$bucket/${safe(path)}").header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() in listOf(400, 409)) {
            check(read(path)?.contentEquals(bytes) == true) { "Immutable cloud object differs" }
        } else check(response.statusCode() in 200..299) { "Private cloud object write failed" }
    }
    override fun delete(path: String) {
        val body = "{\"prefixes\":[\"${safe(path)}\"]}"
        val response = http.send(request("/object/$bucket").header("Content-Type", "application/json")
            .method("DELETE", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() in 200..299 || response.statusCode() == 404) { "Cloud object cleanup failed" }
    }
    override fun checkReady() {
        val response = http.send(request("/bucket/$bucket").GET().build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Private cloud bucket unavailable (${response.statusCode()})" }
        val json = kotlinx.serialization.json.Json.parseToJsonElement(response.body()) as kotlinx.serialization.json.JsonObject
        check(json["public"].toString() == "false") { "Cloud bucket must be private" }
    }
}

/** Shared bounded cache; Store5 owns object reads/writes, including publication before SQL commit. */
class CloudObjectRepository(private val storage: CloudObjectStorage) {
    private val cache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var cachedBytes = 0L
    private val store = MutableStoreBuilder.from<String, List<ByteArray>, List<ByteArray>, List<ByteArray>>(
        fetcher = Fetcher.of { _: String -> error("Objects use the private storage source of truth") },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key: String -> flow { emit(storage.read(key)?.let { listOf(it) } ?: emptyList()) } },
            writer = { key, values -> storage.write(key, values.single()) },
            delete = { key -> storage.delete(key) }
        ), converter = Converter.Builder<List<ByteArray>, List<ByteArray>, List<ByteArray>>().fromNetworkToLocal { it }.fromOutputToLocal { it }.build()
    ).disableCache().build(updater = Updater.by<String, List<ByteArray>, Unit>(post = { _, _ -> UpdaterResult.Success.Typed(Unit) }))
    private fun path(household: String, id: String) = MessageDigest.getInstance("SHA-256").digest(household.toByteArray()).joinToString("") { "%02x".format(it) } + "/" + id
    private data class RangeKey(val path: String, val offset: Int)
    private val ranges = MutableStoreBuilder.from<RangeKey, List<ByteArray>, List<ByteArray>, List<ByteArray>>(
        fetcher = Fetcher.of { _: RangeKey -> error("Object ranges use private storage source of truth") },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key: RangeKey -> flow { emit(listOf(storage.readRange(key.path, key.offset, 1024 * 1024))) } },
            writer = { _: RangeKey, _: List<ByteArray> -> error("Object ranges are immutable") }
        ), converter = Converter.Builder<List<ByteArray>, List<ByteArray>, List<ByteArray>>().fromNetworkToLocal { it }.fromOutputToLocal { it }.build()
    ).disableCache().build(updater = Updater.by<RangeKey, List<ByteArray>, Unit>(post = { _, _ -> error("Object ranges are immutable") }))
    private suspend fun slice(path: String, offset: Int, length: Int): ByteArray {
        require(offset >= 0 && length >= 0)
        val result = ByteArray(length)
        var copied = 0
        while (copied < length) {
            val position = offset + copied
            val page = position / (1024 * 1024) * (1024 * 1024)
            val cacheKey = "$path@$page"
            val cached = synchronized(cache) { cache[cacheKey] }
            val bytes = cached ?: ranges.readLocalSourceOfTruth(RangeKey(path, page)).single().also { loaded ->
                synchronized(cache) {
                    cache.remove(cacheKey)?.let { cachedBytes -= it.size }
                    while (cachedBytes + loaded.size > 64 * 1024 * 1024 && cache.isNotEmpty()) {
                        val first = cache.entries.iterator(); cachedBytes -= first.next().value.size; first.remove()
                    }
                    cache[cacheKey] = loaded; cachedBytes += loaded.size
                }
            }
            val within = position - page
            val count = minOf(length - copied, bytes.size - within)
            require(count > 0) { "Truncated cloud object" }
            bytes.copyInto(result, copied, within, within + count); copied += count
        }
        return result
    }
    suspend fun chunk(household: String, id: String, count: Int, index: Int): ByteArray {
        require(count in 1..finance.shilling.core.backup.BACKUP_MAX_CHUNKS && index in 0 until count)
        val path = path(household, id)
        val headerSize = 4 + count * 4
        val header = java.nio.ByteBuffer.wrap(slice(path, 0, headerSize))
        require(header.int == count)
        var offset = headerSize
        repeat(count) { current ->
            val size = header.int
            require(size in 1..(finance.shilling.core.backup.BACKUP_CHUNK_BYTES + 56))
            if (current == index) return slice(path, offset, size)
            offset += size
        }
        error("Invalid cloud chunk")
    }
    suspend fun write(household: String, id: String, bytes: ByteArray) {
        val key = path(household, id)
        store.write(StoreWriteRequest.of<String, List<ByteArray>, Unit>(key, listOf(bytes)))
        // A failed object write must never become a committed SQL manifest.
        check(store.readLocalSourceOfTruth(key).singleOrNull()?.contentEquals(bytes) == true) { "Cloud object publication failed" }
    }
    suspend fun delete(household: String, id: String) {
        val key = path(household, id)
        store.clear(key)
        synchronized(cache) {
            val iterator = cache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.startsWith("$key@")) { cachedBytes -= entry.value.size; iterator.remove() }
            }
        }
    }
    fun checkReady() = storage.checkReady()
}
