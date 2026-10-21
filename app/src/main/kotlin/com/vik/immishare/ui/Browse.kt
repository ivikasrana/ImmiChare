package com.vik.immishare.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vik.immishare.api.ApiError
import com.vik.immishare.api.Asset
import com.vik.immishare.api.Immich
import com.vik.immishare.api.LinkInfo
import com.vik.immishare.api.Thumbs
import com.vik.immishare.data.Links
import com.vik.immishare.data.ShareLink
import com.vik.immishare.data.looksLocal
import com.vik.immishare.work.Item
import com.vik.immishare.work.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Instant

private val detailGate = Semaphore(4)

private suspend fun fetchDetails(api: Immich, ids: Collection<String>, into: SnapshotStateMap<String, Asset>, loading: SnapshotStateList<String>) = coroutineScope {
    ids.filter { it !in into && it !in loading }.forEach { id ->
        loading += id
        launch {
            try {
                detailGate.withPermit {
                    withContext(Dispatchers.IO) { runCatching { api.asset(id) }.getOrNull() }?.let { into[id] = it }
                }
            } finally {
                loading -= id
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun Browse(link: ShareLink, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val saved = remember(link) { Links.find(ctx, link) }
    val api = remember(link) { Immich(ctx, link, saved?.token.orEmpty()) }
    var info by remember(link) { mutableStateOf<LinkInfo?>(null) }
    var error by remember(link) { mutableStateOf("") }
    var needPassword by remember(link) { mutableStateOf(false) }
    var reload by remember(link) { mutableIntStateOf(0) }
    val selected = remember(link) { mutableStateMapOf<String, Boolean>() }
    val details = remember(link) { mutableStateMapOf<String, Asset>() }
    val loading = remember(link) { mutableStateListOf<String>() }
    var inspect by remember(link) { mutableStateOf<String?>(null) }
    val startJob = rememberStartJob()
    var lanPrompted by remember(link) { mutableStateOf(false) }
    val lan = rememberLanRequest { reload++ }

    LaunchedEffect(link, reload) {
        if (!lanPrompted && link.looksLocal() && lanBlocked(ctx)) {
            lanPrompted = true
            lan()
            return@LaunchedEffect
        }
        error = ""
        info = null
        runCatching { withContext(Dispatchers.IO) { api.linkInfo() } }
            .onSuccess {
                info = it
                it.assets.forEach { a -> if (a.size != null && a.fileName != null) details[a.id] = a }
                saveLinkInfo(ctx, link, it, api.token)
            }
            .onFailure {
                if (it is ApiError.PasswordRequired) needPassword = true else error = describe(it, saved)
            }
    }

    val current = info
    val assets = current?.assets.orEmpty()
    val chosen = assets.filter { selected[it.id] == true }
    fun select(list: List<Asset>) {
        list.forEach { selected[it.id] = true }
        scope.launch { fetchDetails(api, list.map { it.id }, details, loading) }
    }
    val known = chosen.mapNotNull { (details[it.id] ?: it).size }
    val totalText = when {
        chosen.isEmpty() -> ""
        known.size < chosen.size -> "${formatSize(known.sum())}+"
        else -> formatSize(known.sum())
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Back", false, onBack)
            Text(
                saved?.nickname?.takeIf { it.isNotBlank() } ?: current?.title ?: link.host,
                color = t.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (assets.isNotEmpty()) {
                if (chosen.size == assets.size) Chip("Clear", false) { selected.clear() }
                else Chip("Select all", false) { select(assets) }
            }
        }
        if (current != null) {
            Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(plural(assets.size, "item"))
                Pill(if (current.allowDownload) "Download allowed" else "No download", if (current.allowDownload) Tone.Green else Tone.Amber)
                Pill(if (current.allowUpload) "Upload allowed" else "No upload", if (current.allowUpload) Tone.Green else Tone.Neutral)
            }
            if (!current.allowDownload) Notice("The owner turned off downloads for this link. You can browse, but not save originals.", modifier = Modifier.padding(bottom = 8.dp))
        }
        when {
            error.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Notice(error)
                AppButton("Try again", { reload++ }, Modifier.fillMaxWidth(), ButtonKind.Secondary)
                if (lanBlocked(ctx)) AppButton("Allow local network", { lan() }, Modifier.fillMaxWidth())
            }
            current == null && !needPassword -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = t.tint) }
            current != null && assets.isEmpty() -> Notice("This link has no photos or videos", error = false)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(assets, key = { it.id }) { asset ->
                Thumb(
                    api, asset, selected[asset.id] == true,
                    onToggle = { if (selected[asset.id] == true) selected.remove(asset.id) else select(listOf(asset)) },
                    onInspect = {
                        inspect = asset.id
                        scope.launch { fetchDetails(api, listOf(asset.id), details, loading) }
                    },
                )
            }
        }
        if (current != null && assets.isNotEmpty()) {
            Tile(Modifier.padding(bottom = 8.dp), padding = PaddingValues(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${chosen.size} selected", color = t.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        if (totalText.isNotEmpty()) Text(totalText, color = t.secondary, fontSize = 13.sp)
                    }
                    AppButton(
                        "Download",
                        {
                            val items = chosen.map { a ->
                                val d = details[a.id] ?: a
                                Item(a.id, d.fileName, d.size, a.isVideo, d.createdAt ?: a.createdAt, d.mimeType)
                            }
                            startJob(Job.create(false, link, api.token, "Download from ${saved?.title ?: current.title}", items))
                            Toast.makeText(ctx, "Downloading ${plural(items.size, "item")} in the background", Toast.LENGTH_SHORT).show()
                            selected.clear()
                        },
                        enabled = current.allowDownload && chosen.isNotEmpty(),
                    )
                }
            }
        }
    }

    if (needPassword) {
        PasswordDialog(link, onClose = { needPassword = false; if (info == null) error = "This link needs a password" }, onUnlocked = { token ->
            api.token = token
            needPassword = false
            reload++
        })
    }
    inspect?.let { id -> details[id] ?: assets.firstOrNull { it.id == id } }?.let { a ->
        AlertDialog(
            onDismissRequest = { inspect = null },
            title = { Text(a.fileName ?: "Details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Wire("Type", if (a.isVideo) "Video" else "Photo")
                    Wire("Size", a.size?.let(::formatSize) ?: if (a.id in loading) "Loading" else "Unknown")
                    a.durationMs?.let { Wire("Duration", formatDuration(it)) }
                    a.createdAt?.let { c -> Wire("Taken", runCatching { formatDate(Instant.parse(c)) }.getOrDefault(c)) }
                }
            },
            confirmButton = { Chip("Close", false) { inspect = null } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumb(api: Immich, asset: Asset, on: Boolean, onToggle: () -> Unit, onInspect: () -> Unit) {
    val t = LocalTokens.current
    var image by remember(asset.id) { mutableStateOf<ImageBitmap?>(Thumbs.cached(api, asset.id)) }
    LaunchedEffect(asset.id) { if (image == null) image = Thumbs.load(api, asset.id) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(t.fill)
            .then(if (on) Modifier.border(3.dp, t.tint, shape) else Modifier)
            .combinedClickable(onClick = onToggle, onLongClick = onInspect),
    ) {
        image?.let { Image(it, asset.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (asset.isVideo) {
            Text(
                "▶ " + (asset.durationMs?.let(::formatDuration) ?: ""),
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                .background(if (on) t.tint else Color.Black.copy(alpha = .25f))
                .border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (on) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
}
