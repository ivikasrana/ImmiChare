package com.vik.immishare.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.vik.immishare.api.ApiError
import com.vik.immishare.api.Immich
import com.vik.immishare.api.LinkInfo
import com.vik.immishare.data.Links
import com.vik.immishare.data.SavedLink
import com.vik.immishare.data.ShareLink
import com.vik.immishare.data.looksLocal
import com.vik.immishare.work.Item
import com.vik.immishare.work.Job
import com.vik.immishare.work.TransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

sealed class Screen {
    data object Home : Screen()
    data class Browse(val link: ShareLink) : Screen()
    data class Upload(val uris: List<Uri>) : Screen()
}

class Incoming(val screen: Screen, val error: String = "")

fun readIntent(intent: Intent?): Incoming? {
    if (intent == null) return null
    val type = intent.type.orEmpty()
    return when (intent.action) {
        Intent.ACTION_SEND if type.startsWith("text/") -> {
            val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT)).joinToString(" ")
            val link = ShareLink.find(text)
            if (link == null) Incoming(Screen.Home, "No Immich share link found. Links look like https://your-server/share/KEY")
            else Incoming(Screen.Browse(link))
        }
        Intent.ACTION_SEND -> {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (uri == null) Incoming(Screen.Home, "Nothing to upload was shared") else Incoming(Screen.Upload(listOf(uri)))
        }
        Intent.ACTION_SEND_MULTIPLE -> {
            val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            if (uris.isEmpty()) Incoming(Screen.Home, "Nothing to upload was shared") else Incoming(Screen.Upload(uris))
        }
        else -> null
    }
}

fun describe(e: Throwable, saved: SavedLink?): String {
    if (e is ApiError.InvalidLink) {
        val expired = saved?.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }?.takeIf { it.isBefore(Instant.now()) }
        if (expired != null) return "This link expired on ${formatDate(expired)}"
    }
    return e.message ?: "Something went wrong"
}

fun formatDate(i: Instant): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(i)

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

fun saveLinkInfo(c: Context, link: ShareLink, info: LinkInfo, token: String, used: Boolean = true) {
    Links.update(c, link, toFront = used) {
        it.copy(
            nickname = it.nickname.ifBlank { info.title },
            token = token,
            allowDownload = info.allowDownload,
            allowUpload = info.allowUpload,
            expiresAt = info.expiresAt,
            lastUsed = if (used) System.currentTimeMillis() else it.lastUsed,
        )
    }
}

private fun granted(c: Context, permission: String) = ContextCompat.checkSelfPermission(c, permission) == PackageManager.PERMISSION_GRANTED

const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

fun lanBlocked(c: Context) = Build.VERSION.SDK_INT >= 37 && !granted(c, LOCAL_NETWORK)

@Composable
fun rememberLanRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    return { launcher.launch(LOCAL_NETWORK) }
}

@Composable
fun rememberStartJob(): (Job) -> Unit {
    val ctx = LocalContext.current
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return { job ->
        if (Build.VERSION.SDK_INT >= 33 && !granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
        TransferService.start(ctx, job)
    }
}

@Composable
fun ImmiShareApp(incoming: Incoming?, onDark: (Boolean) -> Unit, onFinish: () -> Unit) {
    val dark = resolveDark()
    LaunchedEffect(dark) { onDark(dark) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var banner by remember { mutableStateOf("") }
    var fromShare by remember { mutableStateOf(false) }
    LaunchedEffect(incoming) {
        if (incoming != null) {
            screen = incoming.screen
            banner = incoming.error
            fromShare = incoming.screen !is Screen.Home
        }
    }
    val back = {
        if (fromShare) onFinish() else screen = Screen.Home
    }
    ImmiShareTheme(dark) {
        Backdrop {
            when (val s = screen) {
                Screen.Home -> Home(banner, onOpen = { banner = ""; fromShare = false; screen = Screen.Browse(it) })
                is Screen.Browse -> {
                    BackHandler { back() }
                    Browse(s.link, onBack = back)
                }
                is Screen.Upload -> {
                    BackHandler { back() }
                    UploadPicker(s.uris, onBack = back, onDone = onFinish)
                }
            }
        }
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

private fun clipboardText(c: Context) =
    c.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(c)?.toString().orEmpty()

@Composable
private fun AddLink(onLink: (ShareLink) -> Unit) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    fun submit(value: String) {
        val link = ShareLink.find(value)
        if (link == null) error = "That is not an Immich share link (https://server/share/KEY)" else {
            error = ""
            text = ""
            onLink(link)
        }
    }
    Field(text, { text = it; error = "" }, Modifier.padding(bottom = 10.dp), "Share link", KeyboardType.Uri, invalid = error.isNotEmpty())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppButton("Paste", { text = clipboardText(ctx); submit(text) }, Modifier.weight(1f), ButtonKind.Ghost)
        AppButton("Open", { submit(text) }, Modifier.weight(1f), enabled = text.isNotBlank())
    }
    Notice(error, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Home(banner: String, onOpen: (ShareLink) -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val links by Links.all(ctx).collectAsState()
    val status by TransferService.Status.text.collectAsState()
    var editing by remember { mutableStateOf<SavedLink?>(null) }
    Page {
        Masthead("ImmiShare")
        Notice(banner)
        if (status.isNotBlank()) Notice(status, error = false)
        Tile {
            SectionTitle("Open a link")
            AddLink(onOpen)
        }
        Tile {
            SectionTitle("Saved links")
            if (links.isEmpty()) Text("Links you open or upload to appear here", color = t.secondary, fontSize = 15.sp)
            links.forEach { item ->
                LinkRow(item, onClick = { onOpen(item.link) }, trailing = { Chip("Edit", false) { editing = item } })
            }
        }
    }
    editing?.let { item -> EditDialog(item, onClose = { editing = null }) }
}

@Composable
fun LinkRow(item: SavedLink, onClick: () -> Unit, selected: Boolean = false, trailing: @Composable () -> Unit = {}) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, color = if (selected) t.tint else t.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.link.base.substringAfter("://"), color = t.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.allowDownload?.let { Pill(if (it) "Download allowed" else "No download", if (it) Tone.Green else Tone.Amber) }
                item.allowUpload?.let { Pill(if (it) "Upload allowed" else "No upload", if (it) Tone.Green else Tone.Amber) }
                if (item.token.isNotEmpty()) Pill("Unlocked")
            }
        }
        trailing()
    }
}

@Composable
private fun EditDialog(item: SavedLink, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var nickname by remember { mutableStateOf(item.nickname) }
    var base by remember { mutableStateOf(item.link.base) }
    val parsed = ShareLink.parse("${base.trim().trimEnd('/')}/share/${item.link.key}")
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit link") },
        text = {
            Column {
                Field(nickname, { nickname = it }, Modifier.padding(bottom = 12.dp), "Nickname")
                Field(base, { base = it }, Modifier.padding(bottom = 12.dp), "Server address", KeyboardType.Uri, invalid = parsed == null)
                AppButton("Delete link", { Links.delete(ctx, item.link); onClose() }, Modifier.fillMaxWidth(), ButtonKind.Danger)
            }
        },
        confirmButton = {
            Chip("Save", true) {
                if (parsed == null) return@Chip
                Links.rename(ctx, item.link, nickname, parsed.base)
                onClose()
            }
        },
        dismissButton = { Chip("Cancel", false, onClose) },
    )
}

@Composable
private fun UploadPicker(uris: List<Uri>, onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val links by Links.all(ctx).collectAsState()
    var chosen by remember { mutableStateOf<ShareLink?>(links.firstOrNull { it.allowUpload == true }?.link) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf<ShareLink?>(null) }
    val checking = remember { mutableStateMapOf<ShareLink, String>() }
    val startJob = rememberStartJob()
    var lanTick by remember { mutableIntStateOf(0) }
    val lan = rememberLanRequest { lanTick++ }

    LaunchedEffect(lanTick) {
        if (lanTick == 0 && lanBlocked(ctx) && links.any { it.link.looksLocal() }) {
            lan()
            return@LaunchedEffect
        }
        links.forEach { item ->
            launch {
                checking[item.link] = "Checking"
                runCatching { withContext(Dispatchers.IO) { Immich(ctx, item.link, item.token).linkInfo(withAssets = false) } }
                    .onSuccess { saveLinkInfo(ctx, item.link, it, item.token, used = false); checking.remove(item.link) }
                    .onFailure { checking[item.link] = describe(it, item) }
            }
        }
    }

    fun go(link: ShareLink) {
        val saved = Links.find(ctx, link)
        busy = true
        error = ""
        scope.launch {
            val api = Immich(ctx, link, saved?.token.orEmpty())
            runCatching { withContext(Dispatchers.IO) { api.linkInfo(withAssets = false) } }
                .onSuccess { info ->
                    saveLinkInfo(ctx, link, info, api.token)
                    if (!info.allowUpload) {
                        error = "This link does not allow uploads"
                    } else {
                        val items = uris.map { uri ->
                            val mime = ctx.contentResolver.getType(uri)
                            Item(uri.toString(), null, null, mime?.startsWith("video/") == true, null, mime)
                        }
                        startJob(Job.create(true, link, api.token, "Upload to ${Links.find(ctx, link)?.title ?: info.title}", items))
                        Toast.makeText(ctx, "Uploading ${plural(uris.size, "file")} in the background", Toast.LENGTH_SHORT).show()
                        onDone()
                    }
                }
                .onFailure { if (it is ApiError.PasswordRequired) password = link else error = describe(it, saved) }
            busy = false
        }
    }

    Page {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("Close", false, onBack)
        }
        Masthead("Upload ${plural(uris.size, "file")}")
        Tile {
            SectionTitle("Choose a link")
            if (links.isEmpty()) Text("No saved links yet, paste one below", color = t.secondary, fontSize = 15.sp)
            links.forEach { item ->
                LinkRow(item, onClick = { chosen = item.link }, selected = chosen == item.link) {
                    val state = checking[item.link]
                    when {
                        state == "Checking" -> Pill("Checking")
                        state != null -> Pill("Error", Tone.Red)
                        chosen == item.link -> Pill("Selected", Tone.Green)
                    }
                }
                checking[item.link]?.takeIf { it != "Checking" }?.let { Notice(it, modifier = Modifier.padding(bottom = 6.dp)) }
            }
        }
        Tile {
            SectionTitle("Or paste a new link")
            AddLink { link ->
                Links.update(ctx, link) { it }
                chosen = link
            }
        }
        Notice(error)
        val pick = chosen
        AppButton(
            if (busy) "Checking link" else "Upload",
            { if (pick != null) go(pick) },
            Modifier.fillMaxWidth(),
            enabled = pick != null && !busy && Links.find(ctx, pick)?.allowUpload != false,
            large = true,
        )
    }
    password?.let { link ->
        PasswordDialog(link, onClose = { password = null }, onUnlocked = { password = null; go(link) })
    }
}

@Composable
fun PasswordDialog(link: ShareLink, onClose: () -> Unit, onUnlocked: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Password required") },
        text = {
            Column {
                Field(value, { value = it; error = "" }, Modifier.padding(bottom = 10.dp), "Link password", KeyboardType.Password, invalid = error.isNotEmpty(), secret = true)
                Notice(error)
            }
        },
        confirmButton = {
            Chip(if (busy) "Checking" else "Unlock", true) {
                if (busy || value.isEmpty()) return@Chip
                busy = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { Immich(ctx, link).login(value) } }
                        .onSuccess { token -> Links.update(ctx, link) { it.copy(token = token) }; onUnlocked(token) }
                        .onFailure { error = describe(it, null) }
                    busy = false
                }
            }
        },
        dismissButton = { Chip("Cancel", false, onClose) },
    )
}
