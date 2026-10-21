package com.vik.immishare.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.IBinder
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vik.immishare.R
import com.vik.immishare.api.ApiError
import com.vik.immishare.api.Immich
import com.vik.immishare.api.UploadStatus
import com.vik.immishare.api.pump
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.time.Instant

class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<File>(Channel.UNLIMITED)
    private var pending = 0
    private var worker: CoJob? = null
    @Volatile private var current: File? = null
    @Volatile private var ongoing: Notification? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ongoing?.let(::startForeground) ?: enterForeground("Preparing", 0, 0)
        if (intent?.action == CANCEL) {
            cancelAll()
            return START_NOT_STICKY
        }
        val path = intent?.getStringExtra(EXTRA_JOB)
        if (intent?.action == RETRY) intent.getIntExtra(EXTRA_NOTIFICATION, 0).takeIf { it != 0 }?.let { manager().cancel(it) }
        if (path != null && File(path).exists()) {
            pending++
            queue.trySend(File(path))
        }
        if (worker == null) worker = scope.launch { drain() }
        if (pending == 0) stopAll()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopAll()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun cancelAll() {
        worker?.cancel()
        worker = null
        pending = 0
        current?.delete()
        while (true) queue.tryReceive().getOrNull()?.delete() ?: break
        stopAll()
    }

    private fun stopAll() {
        Status.text.value = ""
        ongoing = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun drain() {
        for (file in queue) {
            current = file
            val job = Job.load(file)
            if (job == null) file.delete() else runCatching { run(job) }.onFailure { if (it is CancellationException) throw it }
            current = null
            withContext(Dispatchers.Main) { if (--pending == 0) stopAll() }
        }
    }

    private suspend fun run(job: Job) {
        val api = Immich(this, job.link, job.token)
        val failed = mutableListOf<Pair<Item, String>>()
        var done = 0
        var duplicates = 0
        val total = job.items.size
        val context = currentCoroutineContext()
        val verb = if (job.upload) "Uploading" else "Downloading"
        job.items.forEachIndexed { i, item ->
            context.ensureActive()
            val text = "$verb ${i + 1}/$total · ${item.name ?: "item ${i + 1}"}"
            progress(job, text, i, total, 0)
            val onProgress = { sent: Long, length: Long ->
                context.ensureActive()
                if (length > 0) progress(job, text, i, total, (sent * 100 / length).toInt())
            }
            val result = withRetry {
                if (job.upload) upload(api, item, onProgress) else download(api, item, onProgress)
            }
            result.fold(
                onSuccess = { if (it == UploadStatus.Duplicate) duplicates++ else done++ },
                onFailure = { failed += item to (it.message ?: it.javaClass.simpleName) },
            )
        }
        summary(job, done, duplicates, failed)
    }

    private suspend fun <T> withRetry(block: suspend () -> T): Result<T> {
        var wait = 2_000L
        repeat(3) { attempt ->
            try {
                return Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val transient = e is ApiError.Unreachable || e is ApiError.NoNetwork || e is ApiError.Server || (e is IOException && e !is ApiError)
                if (!transient || attempt == 2) return Result.failure(e)
                delay(wait)
                wait *= 3
            }
        }
        return Result.failure(IllegalStateException())
    }

    private fun progress(job: Job, text: String, index: Int, total: Int, pct: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 500 && pct in 1..99) return
        lastNotify = now
        Status.text.value = text
        enterForeground(text, index * 100 + pct.coerceIn(0, 100), total * 100, job.title)
    }

    private fun download(api: Immich, item: Item, onProgress: (Long, Long) -> Unit): UploadStatus = api.original(item.ref) { conn ->
        val disposition = fileNameFrom(conn.getHeaderField("Content-Disposition"))
        val serverMime = conn.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
        val mime = serverMime ?: item.mime ?: guessMime(item.name ?: disposition)
        val video = mime?.startsWith("video/") ?: item.video
        val name = sanitize(item.name ?: disposition ?: (item.ref + extensionFor(mime, video)))
        val length = conn.contentLengthLong.takeIf { it > 0 } ?: item.size ?: -1L
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val folder = if (video) "Movies/ImmiShare/" else "Pictures/ImmiShare/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName(collection, folder, name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            if (mime != null) put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            item.createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
        }
        val target = contentResolver.insert(collection, values) ?: throw IOException("Could not create file in $folder")
        try {
            val output = contentResolver.openOutputStream(target) ?: throw IOException("Could not write $name")
            output.use { out -> conn.inputStream.use { it.pump(out) { read -> onProgress(read, length) } } }
            contentResolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { contentResolver.delete(target, null, null) }
            throw e
        }
        UploadStatus.Created
    }

    private fun uniqueName(collection: Uri, folder: String, name: String): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        var candidate = name
        var n = 1
        while (exists(collection, folder, candidate)) candidate = "$stem (${n++})$ext"
        return candidate
    }

    private fun exists(collection: Uri, folder: String, name: String): Boolean = runCatching {
        contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(folder, name), null,
        )?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    private fun upload(api: Immich, item: Item, onProgress: (Long, Long) -> Unit): UploadStatus {
        val uri = Uri.parse(item.ref)
        val meta = Meta.of(this, uri)
        val name = item.name ?: meta.name ?: "upload${extensionFor(item.mime, item.video)}"
        val mime = item.mime ?: contentResolver.getType(uri) ?: guessMime(name) ?: "application/octet-stream"
        val length = meta.size ?: -1L
        val created = meta.taken ?: meta.modified ?: Instant.now()
        return api.upload(
            fileName = name,
            mimeType = mime,
            length = length,
            createdAt = created,
            modifiedAt = meta.modified ?: created,
            durationMs = if (mime.startsWith("video/")) meta.durationMs else null,
            data = { contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $name") },
            onProgress = { sent -> onProgress(sent, length) },
        )
    }

    private fun summary(job: Job, done: Int, duplicates: Int, failed: List<Pair<Item, String>>) {
        val verb = if (job.upload) "Uploaded" else "Downloaded"
        val parts = buildList {
            add("$verb $done")
            if (job.upload) add("$duplicates duplicate${if (duplicates == 1) "" else "s"}")
            if (failed.isNotEmpty()) add("${failed.size} failed")
        }
        val text = parts.joinToString(" · ")
        val detail = if (failed.isEmpty()) text else text + "\n" + failed.take(5).joinToString("\n") { (item, err) -> "${item.name ?: item.ref.substringAfterLast('/')}: $err" }
        val id = NOTIFY_BASE + (job.id.hashCode() and 0xFFFFF)
        val b = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(job.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openApp())
            .setAutoCancel(true)
        if (failed.isEmpty()) {
            job.file(this).delete()
        } else {
            val retryJob = job.withItems(failed.map { it.first })
            val file = retryJob.save(this)
            val retry = Intent(this, TransferService::class.java).setAction(RETRY).putExtra(EXTRA_JOB, file.path).putExtra(EXTRA_NOTIFICATION, id)
            if (job.upload) grant(retry, retryJob.items.map { Uri.parse(it.ref) })
            b.addAction(0, "Retry", PendingIntent.getForegroundService(this, id, retry, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        createChannels()
        runCatching { manager().notify(id, b.build()) }
        Status.text.value = text
    }

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun openApp() = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE)

    private fun createChannels() {
        manager().createNotificationChannel(NotificationChannel(CHANNEL, "Transfers in progress", NotificationManager.IMPORTANCE_LOW))
        manager().createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished transfers", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun enterForeground(text: String, progress: Int, max: Int, title: String = "ImmiShare") {
        createChannels()
        val cancel = PendingIntent.getService(this, 1, Intent(this, TransferService::class.java).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, max == 0)
            .addAction(0, "Cancel", cancel)
            .build()
        startForeground(n)
    }

    private fun startForeground(n: Notification) {
        ongoing = n
        ServiceCompat.startForeground(this, FOREGROUND_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    object Status {
        val text = MutableStateFlow("")
    }

    companion object {
        private const val CHANNEL = "transfer"
        private const val CHANNEL_DONE = "transfer-done"
        private const val FOREGROUND_ID = 1
        private const val NOTIFY_BASE = 1000
        private const val EXTRA_JOB = "job"
        private const val EXTRA_NOTIFICATION = "notification"
        const val RETRY = "com.vik.immishare.RETRY"
        const val CANCEL = "com.vik.immishare.CANCEL"

        fun start(context: Context, job: Job) {
            val file = job.save(context)
            val intent = Intent(context, TransferService::class.java).putExtra(EXTRA_JOB, file.path)
            if (job.upload) grant(intent, job.items.map { Uri.parse(it.ref) })
            ContextCompat.startForegroundService(context, intent)
        }

        private fun grant(intent: Intent, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val clip = ClipData.newRawUri("files", uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            intent.clipData = clip
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        fun fileNameFrom(header: String?): String? {
            if (header.isNullOrBlank()) return null
            Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""").find(header)?.let {
                return runCatching { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") }.getOrNull()
            }
            return Regex("""filename\s*=\s*"?([^";]+)"?""").find(header)?.groupValues?.get(1)?.trim()
        }

        fun sanitize(name: String) = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().ifEmpty { "file" }

        fun guessMime(name: String?): String? = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }

        fun extensionFor(mime: String?, video: Boolean): String =
            mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.let { ".$it" } ?: if (video) ".mp4" else ".jpg"
    }
}

class Meta(val name: String?, val size: Long?, val taken: Instant?, val modified: Instant?, val durationMs: Long?) {
    companion object {
        fun of(c: Context, uri: Uri): Meta {
            var name: String? = null
            var size: Long? = null
            var taken: Instant? = null
            var modified: Instant? = null
            var duration: Long? = null
            runCatching {
                c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                    if (!cur.moveToFirst()) return@use
                    fun col(n: String) = cur.getColumnIndex(n).takeIf { it >= 0 && !cur.isNull(it) }
                    col(OpenableColumns.DISPLAY_NAME)?.let { name = cur.getString(it) }
                    col(OpenableColumns.SIZE)?.let { size = cur.getLong(it).takeIf { s -> s >= 0 } }
                    col(MediaStore.MediaColumns.DATE_TAKEN)?.let { taken = cur.getLong(it).takeIf { v -> v > 0 }?.let(Instant::ofEpochMilli) }
                    col(MediaStore.MediaColumns.DATE_MODIFIED)?.let { modified = cur.getLong(it).takeIf { v -> v > 0 }?.let(Instant::ofEpochSecond) }
                    col(MediaStore.MediaColumns.DURATION)?.let { duration = cur.getLong(it).takeIf { v -> v > 0 } }
                }
            }
            if (size == null) size = runCatching { c.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize.takeIf { s -> s >= 0 } } }.getOrNull()
            if (duration == null && c.contentResolver.getType(uri)?.startsWith("video/") == true) {
                duration = runCatching {
                    MediaMetadataRetriever().run {
                        try {
                            setDataSource(c, uri)
                            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        } finally {
                            release()
                        }
                    }
                }.getOrNull()
            }
            return Meta(name, size, taken, modified, duration)
        }
    }
}
