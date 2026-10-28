package com.vik.immishare.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.vik.immishare.data.ShareLink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

class Asset(
    val id: String,
    val isVideo: Boolean,
    val durationMs: Long?,
    val fileName: String?,
    val size: Long?,
    val createdAt: String?,
    val mimeType: String?,
)

class LinkInfo(
    val id: String,
    val isAlbum: Boolean,
    val allowDownload: Boolean,
    val allowUpload: Boolean,
    val showMetadata: Boolean,
    val expiresAt: String?,
    val description: String?,
    val albumId: String?,
    val albumName: String?,
    val assets: List<Asset>,
) {
    val title: String get() = albumName?.takeIf { it.isNotBlank() } ?: description?.takeIf { it.isNotBlank() } ?: "Shared photos"
}

enum class UploadStatus { Created, Duplicate }

fun InputStream.pump(out: OutputStream, onProgress: (Long) -> Unit) {
    val buf = ByteArray(256 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
        onProgress(total)
    }
}

sealed class ApiError(message: String) : IOException(message) {
    class NoNetwork : ApiError("No internet connection")
    class Unreachable(detail: String) : ApiError("Cannot reach the server ($detail)")
    class Tls(detail: String) : ApiError("Secure connection failed: $detail")
    class PasswordRequired : ApiError("This link needs a password")
    class WrongPassword : ApiError("Wrong password")
    class InvalidLink : ApiError("This link is invalid or has expired")
    class Forbidden(detail: String) : ApiError(detail.ifBlank { "This link does not allow that" })
    class NotFound : ApiError("Not found on the server")
    class Server(val code: Int, detail: String) : ApiError("Server error $code${if (detail.isBlank()) "" else ": $detail"}")
}

class Immich(context: Context, val link: ShareLink, var token: String = "") {
    private val app = context.applicationContext

    private fun online(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun url(path: String, query: Map<String, String> = emptyMap()): String {
        val params = (mapOf("key" to link.key) + query).entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return "${link.base}/api$path?$params"
    }

    fun open(path: String, query: Map<String, String> = emptyMap(), method: String = "GET", readTimeout: Int = 30_000): HttpURLConnection {
        if (!online()) throw ApiError.NoNetwork()
        val c = URL(url(path, query)).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = readTimeout
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "ImmiShare")
        if (token.isNotEmpty()) c.setRequestProperty("Cookie", "$TOKEN_COOKIE=$token")
        return c
    }

    fun <T> call(c: HttpURLConnection, body: ((OutputStream) -> Unit)? = null, read: (HttpURLConnection) -> T): T {
        try {
            if (body != null) {
                c.doOutput = true
                c.outputStream.use(body)
            }
            val code = c.responseCode
            if (code in 200..299) return read(c)
            throw errorFor(code, c.errorStream?.use { it.readBytes().decodeToString() }.orEmpty())
        } catch (e: ApiError) {
            throw e
        } catch (e: SSLException) {
            throw ApiError.Tls(e.message ?: "certificate not trusted")
        } catch (e: IOException) {
            throw mapNetwork(e)
        } finally {
            if (body != null) runCatching { c.disconnect() }
        }
    }

    private fun mapNetwork(e: IOException): ApiError = when {
        !online() -> ApiError.NoNetwork()
        e is UnknownHostException -> ApiError.Unreachable("unknown host ${link.host}")
        e is ConnectException || e is NoRouteToHostException -> ApiError.Unreachable("connection refused")
        e is SocketTimeoutException -> ApiError.Unreachable("timed out")
        else -> ApiError.Unreachable(e.message ?: e.javaClass.simpleName)
    }

    private fun errorFor(code: Int, raw: String): ApiError {
        val message = runCatching {
            val o = JSONObject(raw)
            val m = o.opt("message")
            if (m is JSONArray) (0 until m.length()).joinToString("; ") { m.getString(it) } else m?.toString().orEmpty()
        }.getOrDefault("")
        return when {
            code == 401 && message.contains("password required", true) -> ApiError.PasswordRequired()
            code == 401 && message.contains("invalid password", true) -> ApiError.WrongPassword()
            code == 401 -> ApiError.InvalidLink()
            code == 403 -> ApiError.Forbidden(message)
            code == 404 -> ApiError.NotFound()
            else -> ApiError.Server(code, message)
        }
    }

    private fun json(c: HttpURLConnection) = c.inputStream.use { it.readBytes().decodeToString() }

    fun linkInfo(withAssets: Boolean = true): LinkInfo {
        val o = JSONObject(call(open("/shared-links/me"), read = ::json))
        val album = o.optJSONObject("album")
        val arr = o.optJSONArray("assets") ?: JSONArray()
        val isAlbum = o.optString("type") == "ALBUM" || album != null
        val assets = (0 until arr.length()).map { parseAsset(arr.getJSONObject(it)) }
        val albumId = album?.optString("id")?.takeIf { it.isNotEmpty() }
        return LinkInfo(
            id = o.optString("id"),
            isAlbum = isAlbum,
            allowDownload = o.optBoolean("allowDownload"),
            allowUpload = o.optBoolean("allowUpload"),
            showMetadata = o.optBoolean("showMetadata", true),
            expiresAt = o.str("expiresAt"),
            description = o.str("description"),
            albumId = albumId,
            albumName = album?.str("albumName"),
            assets = if (withAssets && isAlbum && assets.isEmpty() && albumId != null) albumAssets(albumId) else assets,
        )
    }

    fun login(password: String): String {
        val c = open("/shared-links/login", method = "POST")
        c.setRequestProperty("Content-Type", "application/json")
        val payload = JSONObject().put("password", password).toString().toByteArray()
        return call(c, { it.write(payload) }) { conn ->
            val cookie = conn.headerFields.entries
                .filter { it.key.equals("Set-Cookie", true) }
                .flatMap { it.value }
                .firstOrNull { it.startsWith("$TOKEN_COOKIE=") }
            val value = cookie?.substringAfter('=')?.substringBefore(';').orEmpty()
            if (value.isEmpty()) throw ApiError.Server(conn.responseCode, "no session returned")
            token = value
            value
        }
    }

    private fun albumAssets(albumId: String): List<Asset> {
        val query = mapOf("albumId" to albumId, "order" to "asc")
        val buckets = JSONArray(call(open("/timeline/buckets", query), read = ::json))
        val names = (0 until buckets.length()).map { buckets.getJSONObject(it).getString("timeBucket") }
        if (names.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(BUCKET_PARALLELISM, names.size))
        try {
            val tasks = names.map { bucket -> Callable { bucketAssets(query + ("timeBucket" to bucket)) } }
            return pool.invokeAll(tasks).flatMap {
                try {
                    it.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun bucketAssets(query: Map<String, String>): List<Asset> {
        val o = JSONObject(call(open("/timeline/bucket", query), read = ::json))
        val ids = o.getJSONArray("id")
        val images = o.optJSONArray("isImage")
        val durations = o.optJSONArray("duration")
        val created = o.optJSONArray("fileCreatedAt")
        return (0 until ids.length()).map { k ->
            Asset(
                id = ids.getString(k),
                isVideo = images?.optBoolean(k, true) == false,
                durationMs = durations?.let { parseDuration(it.opt(k)) },
                fileName = null,
                size = null,
                createdAt = created?.takeUnless { it.isNull(k) }?.optString(k),
                mimeType = null,
            )
        }
    }

    fun asset(id: String): Asset = parseAsset(JSONObject(call(open("/assets/$id"), read = ::json)))

    fun thumbnail(id: String): ByteArray = call(open("/assets/$id/thumbnail", mapOf("size" to "thumbnail"))) { c ->
        c.inputStream.use { it.readBytes() }
    }

    fun <T> original(id: String, read: (HttpURLConnection) -> T): T = call(open("/assets/$id/original", readTimeout = 120_000), read = read)

    fun upload(
        fileName: String,
        mimeType: String,
        length: Long,
        createdAt: Instant,
        modifiedAt: Instant,
        durationMs: Long?,
        data: () -> InputStream,
        onProgress: (Long) -> Unit,
    ): UploadStatus {
        val boundary = "ImmiShare" + UUID.randomUUID().toString().replace("-", "")
        val fields = buildList {
            add("fileCreatedAt" to createdAt.toString())
            add("fileModifiedAt" to modifiedAt.toString())
            add("filename" to fileName)
            if (durationMs != null) add("duration" to durationMs.toString())
        }
        val safeName = fileName.replace("\"", "_").replace("\r", "").replace("\n", "")
        val head = buildString {
            fields.forEach { (k, v) -> append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"assetData\"; filename=\"$safeName\"\r\nContent-Type: $mimeType\r\n\r\n")
        }.toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val c = open("/assets", method = "POST", readTimeout = 300_000)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        if (length >= 0) c.setFixedLengthStreamingMode(head.size + length + tail.size) else c.setChunkedStreamingMode(256 * 1024)
        return call(c, { out ->
            out.write(head)
            data().use { it.pump(out, onProgress) }
            out.write(tail)
        }) { conn ->
            val status = runCatching { JSONObject(json(conn)).optString("status") }.getOrDefault("")
            if (status == "duplicate" || conn.responseCode == 200) UploadStatus.Duplicate else UploadStatus.Created
        }
    }

    companion object {
        const val TOKEN_COOKIE = "immich_shared_link_token"
        private const val BUCKET_PARALLELISM = 4

        private fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

        fun parseDuration(v: Any?): Long? = when (v) {
            null, JSONObject.NULL -> null
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: run {
                val parts = v.split(':')
                if (parts.size != 3) return null
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val s = parts[2].toDoubleOrNull() ?: return null
                ((h * 3600 + m * 60) * 1000 + (s * 1000).toLong())
            }
            else -> null
        }?.takeIf { it > 0 }

        fun parseAsset(o: JSONObject): Asset {
            val exif = o.optJSONObject("exifInfo")
            val size = exif?.takeUnless { it.isNull("fileSizeInByte") }?.optLong("fileSizeInByte", -1)?.takeIf { it >= 0 }
            return Asset(
                id = o.getString("id"),
                isVideo = o.optString("type") == "VIDEO",
                durationMs = parseDuration(o.opt("duration")),
                fileName = o.str("originalFileName"),
                size = size,
                createdAt = o.str("fileCreatedAt") ?: o.str("localDateTime"),
                mimeType = o.str("originalMimeType"),
            )
        }
    }
}
