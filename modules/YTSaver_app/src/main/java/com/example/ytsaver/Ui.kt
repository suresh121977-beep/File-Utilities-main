package com.example.ytsaver

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

@Composable
fun AppRoot(vm: VM) {
    var tab by remember { mutableIntStateOf(0) }
    BackHandler(vm.selected != null && tab == 0) { vm.selected = null }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == 0, { tab = 0 }, { Text("🔎") }, label = { Text("Search") })
            NavigationBarItem(tab == 1, { tab = 1 }, { Text("💾") }, label = { Text("Saved") })
        }
    }) { pad ->
        Column(Modifier.padding(pad).statusBarsPadding().fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when {
                    tab == 1 -> SavedTab(vm)
                    vm.selected != null -> Detail(vm)
                    else -> SearchTab(vm)
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                if (vm.busy) {
                    if (vm.progress > 0f) LinearProgressIndicator(
                        progress = { (vm.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    ) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(vm.status, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SearchTab(vm: VM) {
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                q, { q = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Search or paste YouTube link") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(q) })
            )
            Spacer(Modifier.width(8.dp))
            Button({ vm.search(q) }, enabled = vm.ready && !vm.busy) { Text("Go") }
        }
        TextButton({ vm.update() }, enabled = !vm.busy) { Text("Update engine") }
        LazyColumn {
            items(vm.results) { it ->
                Row(Modifier.fillMaxWidth().clickable { vm.open(it) }.padding(vertical = 6.dp)) {
                    AsyncImage(
                        it.thumb, null,
                        Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(it.title, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(it.meta, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun <T> Chips(opts: List<T>, sel: T, label: (T) -> String, on: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        opts.forEach { FilterChip(selected = it == sel, onClick = { on(it) }, label = { Text(label(it)) }) }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun Detail(vm: VM) {
    val item = vm.selected ?: return
    val ctx = LocalContext.current
    val stream = vm.stream
    var audio by remember { mutableStateOf(false) }
    var height by remember { mutableStateOf<Int?>(null) }
    var container by remember { mutableStateOf("mp4") }
    var afmt by remember { mutableStateOf("mp3") }
    var cut by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf("0:00") }
    var end by remember { mutableStateOf("0:30") }
    var frameAt by remember { mutableStateOf("0:00") }

    val player = remember(stream) {
        stream?.let { s ->
            val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            val f = ProgressiveMediaSource.Factory(http)
            val v = f.createMediaSource(MediaItem.fromUri(s.video))
            val src = if (s.audio != null)
                MergingMediaSource(v, f.createMediaSource(MediaItem.fromUri(s.audio))) else v
            ExoPlayer.Builder(ctx).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        vm.status = "Player error: " + error.errorCodeName
                    }
                })
                setMediaSource(src); prepare(); playWhenReady = true
            }
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    fun now() = Engine.fmt(((player?.currentPosition ?: 0L) / 1000).toInt())

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(item.title, style = MaterialTheme.typography.titleMedium)
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            if (player != null) AndroidView(
                { PlayerView(it).apply { this.player = player } },
                Modifier.fillMaxSize(), update = { it.player = player }
            ) else CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        Chips(listOf(false, true), audio, { if (it) "Audio" else "Video" }) { audio = it }
        if (audio) {
            Chips(listOf("mp3", "m4a", "opus", "flac", "wav"), afmt, { it }) { afmt = it }
        } else {
            Chips(listOf<Int?>(null, 1080, 720, 480, 360), height, { it?.let { h -> "${h}p" } ?: "Best" }) { height = it }
            Chips(listOf("mp4", "mkv", "webm"), container, { it }) { container = it }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(cut, { cut = it })
            Text("Cut section (minutes:seconds)")
        }
        if (cut) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it }, Modifier.weight(1f), singleLine = true, label = { Text("Start") })
                TextButton({ start = now() }) { Text("Now") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(end, { end = it }, Modifier.weight(1f), singleLine = true, label = { Text("End") })
                TextButton({ end = now() }) { Text("Now") }
            }
        }

        Button(
            {
                var s: Int? = null
                var e: Int? = null
                if (cut) {
                    s = Engine.parseTime(start); e = Engine.parseTime(end)
                    if (s == null || e == null || e <= s) {
                        vm.status = "Check start/end times (end must be after start)"
                        return@Button
                    }
                }
                vm.download(item, Engine.Spec(audio, height, container, afmt, s, e))
            },
            enabled = vm.ready && !vm.busy, modifier = Modifier.fillMaxWidth()
        ) { Text(if (audio) "Download audio" else "Download video") }

        HorizontalDivider()
        Text("Save a frame as image", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(frameAt, { frameAt = it }, Modifier.weight(1f), singleLine = true, label = { Text("Time") })
            TextButton({ frameAt = now() }) { Text("Now") }
        }
        Button(
            {
                val t = Engine.parseTime(frameAt)
                if (t == null) vm.status = "Invalid time" else vm.frame(t)
            },
            enabled = stream != null && !vm.busy, modifier = Modifier.fillMaxWidth()
        ) { Text("Save frame (JPG)") }
    }
}

@Composable
fun SavedTab(vm: VM) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.refreshSaved() }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        items(vm.saved) { s ->
            Text(
                s.name,
                Modifier.fillMaxWidth().clickable {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, s.mime)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }.padding(vertical = 12.dp)
            )
            HorizontalDivider()
        }
    }
}
