@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.files

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.*

val ROOT: File get() = Environment.getExternalStorageDirectory()
val TYPES = mapOf(
    "Images" to setOf("jpg", "jpeg", "png", "gif", "webp", "bmp"),
    "Videos" to setOf("mp4", "mkv", "3gp", "webm", "avi", "mov"),
    "Audio" to setOf("mp3", "wav", "m4a", "aac", "ogg", "flac"),
    "Documents" to setOf("pdf", "xls", "xlsx", "csv", "txt", "doc", "docx", "html", "md", "json"))
val DOCX = listOf("pdf", "xls", "csv", "txt", "docx", "html", "md", "json")

val HOME_CATEGORIES = listOf("🖼️" to "Images", "🎬" to "Videos", "🎵" to "Audio", "📄" to "Documents",
    "📱" to "Storage", "⭐" to "Favourites", "⚡" to "Quick view", "🗂️" to "Organizer",
    "👯" to "Duplicates", "📲" to "Apps", "🔗" to "Shortcuts", "💾" to "Backup", "🧺" to "Pick & Drop")
val SPECIAL_SCREENS = setOf("Home", "Organizer", "Duplicates", "Apps", "Quick view", "Shortcuts", "Pick & Drop")
private fun catKey(s: String) = s.lowercase().replace("&", "and").replace(Regex("[^a-z]"), "")
fun homeCat(spoken: String): String? {
    val alias = mapOf("photos" to "images", "pictures" to "images", "gallery" to "images", "music" to "audio", "songs" to "audio",
        "docs" to "documents", "files" to "storage", "internalstorage" to "storage", "favorites" to "favourites",
        "basket" to "pickanddrop", "pickdrop" to "pickanddrop", "quick" to "quickview", "duplicate" to "duplicates", "shortcut" to "shortcuts")
    val k = catKey(spoken)
    val want = alias[k] ?: k
    return HOME_CATEGORIES.firstOrNull { catKey(it.second) == want }?.second
}

class FI(val f: File, val dir: Boolean, val len: Long, val mod: Long)

fun size(b: Long): String {
    if (b < 1024) return "$b B"
    val u = listOf("KB", "MB", "GB", "TB"); var v = b.toDouble(); var i = -1
    while (v >= 1024 && i < 3) { v /= 1024; i++ }
    return "%.1f %s".format(v, u[i])
}
fun date(t: Long): String = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(t))
fun created(f: File): Long = try { Files.readAttributes(f.toPath(), BasicFileAttributes::class.java).creationTime().toMillis() } catch (e: Exception) { f.lastModified() }
fun sha(s: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
fun uniq(dir: File, name: String): File {
    var f = File(dir, name); var i = 1
    val b = f.nameWithoutExtension; val e = f.extension.let { if (it.isEmpty()) "" else ".$it" }
    while (f.exists()) { f = File(dir, "$b ($i)$e"); i++ }
    return f
}
/** Name for a file/folder dropped into [dir]. Free name -> unchanged. Taken -> "name (copied 2026-10-30 11-05-22).ext", so same-name files stay easy to tell apart. */
fun uniqStamp(dir: File, name: String, verb: String, isDir: Boolean = false): File {
    val first = File(dir, name)
    if (!first.exists()) return first
    val dot = name.lastIndexOf('.')
    val stem = if (!isDir && dot > 0) name.substring(0, dot) else name
    val ext = if (!isDir && dot > 0) name.substring(dot) else ""
    val tag = stamp()
    var f = File(dir, "$stem ($verb $tag)$ext"); var i = 2
    while (f.exists()) { f = File(dir, "$stem ($verb $tag $i)$ext"); i++ }
    return f
}
fun copyRaw(s: File, d: File) {
    if (s.isDirectory) { d.mkdirs(); s.listFiles()?.forEach { copyRaw(it, File(d, it.name)) } }
    else s.copyTo(d, false, 1 shl 20)
}
fun copyTo(f: File, dir: File) {
    if (f.isDirectory && dir.path.startsWith(f.path)) return
    copyRaw(f, uniqStamp(dir, f.name, "copied", f.isDirectory))
}
fun moveTo(f: File, dir: File) {
    if (f.isDirectory && dir.path.startsWith(f.path)) return
    val t = uniqStamp(dir, f.name, "moved", f.isDirectory)
    if (!f.renameTo(t)) { copyRaw(f, t); f.deleteRecursively() }
}
fun zip(fs: List<File>, out: File) = ZipOutputStream(out.outputStream().buffered(1 shl 16)).use { z ->
    z.setLevel(1)
    fs.forEach { r -> r.walkTopDown().filter { it.isFile }.forEach { f ->
        z.putNextEntry(ZipEntry(f.relativeTo(r.parentFile!!).path)); f.inputStream().use { it.copyTo(z, 1 shl 16) }; z.closeEntry() } }
}
fun unzip(f: File, dir: File) {
    dir.mkdirs()
    ZipInputStream(f.inputStream().buffered(1 shl 16)).use { z ->
        var e = z.nextEntry
        while (e != null) {
            val t = File(dir, e.name)
            if (t.canonicalPath.startsWith(dir.canonicalPath)) {
                if (e.isDirectory) t.mkdirs() else { t.parentFile?.mkdirs(); t.outputStream().use { z.copyTo(it, 1 shl 16) } }
            }
            e = z.nextEntry
        }
    }
}
fun mime(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
fun uri(c: Context, f: File): Uri = FileProvider.getUriForFile(c, c.packageName + ".provider", f)
fun openFile(c: Context, f: File) = try {
    c.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri(c, f), mime(f))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"))
} catch (e: Exception) { Toast.makeText(c, "No app can open this", Toast.LENGTH_SHORT).show() }
fun share(c: Context, fs: List<File>) {
    val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(fs.filter { it.isFile }.map { uri(c, it) }))
    c.startActivity(Intent.createChooser(i, "Share"))
}
fun hasAccess(c: Context) = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

class MainActivity : ComponentActivity() {
    override fun onCreate(s: android.os.Bundle?) {
        super.onCreate(s)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Root() } } }
    }
}

@Composable fun Root() {
    val c = LocalContext.current
    val prefs = remember { c.getSharedPreferences("p", 0) }
    var ok by remember { mutableStateOf(hasAccess(c)) }
    var unlocked by remember { mutableStateOf(prefs.getString("pw", null) == null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) ok = hasAccess(c) }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok = hasAccess(c) }
    if (!ok) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 30) c.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + c.packageName)))
                else perm.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }) { Text("Grant file access") }
        }
    } else if (!unlocked) {
        var t by remember { mutableStateOf("") }; var err by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("🔒 Enter password", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(t, { t = it; err = false }, singleLine = true, isError = err, visualTransformation = PasswordVisualTransformation())
            Spacer(Modifier.height(12.dp))
            Button({ if (sha(t) == prefs.getString("pw", "")) unlocked = true else err = true }) { Text("Unlock") }
        }
    } else App(prefs)
}

@Composable fun App(prefs: SharedPreferences) {
    val c = LocalContext.current
    val favs = remember { mutableStateListOf<String>().apply { addAll(prefs.getStringSet("fav", emptySet())!!) } }
    var all by remember { mutableStateOf(emptyList<File>()) }
    var dirs by remember { mutableStateOf(emptyList<File>()) }
    var tick by remember { mutableIntStateOf(0) }
    var ver by remember { mutableIntStateOf(0) }
    val sf = remember { (c as? ComponentActivity)?.intent?.getStringExtra("path")?.let { File(it) } }
    var openDir by remember { mutableStateOf(sf?.takeIf { it.isDirectory }) }
    var screen by remember { mutableStateOf(if (openDir != null) "Storage" else "Home") }
    var query by remember { mutableStateOf("") }
    var lockDlg by remember { mutableStateOf(false) }
    val basket = remember { Basket() }
    val voice = remember { VoiceHost(c) }
    var helpDlg by remember { mutableStateOf(false) }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) voice.start() else Toast.makeText(c, "Microphone permission is needed for voice commands", Toast.LENGTH_LONG).show()
    }
    fun toggleVoice() {
        if (voice.on) { voice.stop(true) }
        else if (hasMicPermission(c)) { voice.autoAsk = true; voice.start() }
        else { micPerm.launch(Manifest.permission.RECORD_AUDIO) }
    }
    val lifeOwner = LocalLifecycleOwner.current
    DisposableEffect(lifeOwner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) voice.stop() }
        lifeOwner.lifecycle.addObserver(o)
        onDispose { lifeOwner.lifecycle.removeObserver(o); voice.shutdown() }
    }
    LaunchedEffect(voice.notice) { voice.notice?.let { Toast.makeText(c, it, Toast.LENGTH_LONG).show() } }
    LaunchedEffect(Unit) { if (sf != null && sf.isFile) openFile(c, sf) }
    LaunchedEffect(tick) {
        val r = withContext(Dispatchers.IO) {
            ROOT.walkTopDown().onEnter { !it.name.startsWith(".") && it.name != "Android" }
                .filter { !it.name.startsWith(".") }.toList().partition { it.isFile }
        }
        all = r.first; dirs = r.second.filter { it != ROOT }; ver++
    }
    BackHandler(screen != "Home") { screen = "Home"; openDir = null }
    val setFav = { fs: List<File>, on: Boolean ->
        fs.forEach { if (on) { if (it.path !in favs) favs.add(it.path) } else favs.remove(it.path) }
        prefs.edit().putStringSet("fav", favs.toSet()).apply()
    }
    // Voice commands for the screens that are not file lists (Home, Organizer, Apps, ...)
    LaunchedEffect(voice.event) {
        val ev = voice.event ?: return@LaunchedEffect
        if (ev.handled || screen !in SPECIAL_SCREENS) return@LaunchedEffect
        ev.handled = true
        val cmd = ev.cmd
        when (cmd) {
            is VCmd.Stop -> voice.stop(true)
            is VCmd.Help -> helpDlg = true
            is VCmd.No -> { if (helpDlg) helpDlg = false else if (screen != "Home") { screen = "Home"; openDir = null } }
            is VCmd.Back, is VCmd.Home -> { if (screen != "Home") { screen = "Home"; openDir = null } }
            is VCmd.Refresh -> tick++
            is VCmd.Search -> { query = cmd.q; openDir = null; screen = "Search" }
            is VCmd.Num -> { if (screen == "Home") HOME_CATEGORIES.getOrNull(cmd.n - 1)?.let { screen = it.second } }
            is VCmd.Text -> { if (screen == "Home") homeCat(cmd.s)?.let { screen = it } }
            is VCmd.Do -> {
                if (screen == "Home" && cmd.verb == Verb.OPEN) {
                    val byNum = cmd.nums.firstOrNull()?.let { HOME_CATEGORIES.getOrNull(it - 1)?.second }
                    val byName = cmd.arg?.let { homeCat(it) }
                    val target = byNum ?: byName
                    if (target != null) { openDir = null; screen = target }
                    else Toast.makeText(c, "Say a category name or number, like “open 1”", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {}
        }
    }
    Box(Modifier.fillMaxSize()) {
    key(screen) {
        when (screen) {
            "Home" -> Home({ n -> screen = n }, { query = it; screen = "Search" }, { lockDlg = true }, basket.items.size)
            "Organizer" -> Organizer(all) { tick++ }
            "Duplicates" -> Duplicates(all) { tick++ }
            "Apps" -> AppsScreen(prefs)
            "Quick view" -> Navigator("view")
            "Shortcuts" -> Navigator("shortcut")
            "Pick & Drop" -> PickDropScreen(basket, { openDir = null; screen = "Storage" }) { tick++ }
            else -> Browser(screen, all, dirs, favs, ver, { tick++ }, setFav, openDir, if (screen == "Search") query else "", basket, voice) { screen = "Home"; openDir = null }
        }
    }
    Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 96.dp), horizontalAlignment = Alignment.End) {
        if (voice.on) {
            VoiceStatusCard(voice.partial, voice.heard, Modifier.widthIn(max = 280.dp))
            Spacer(Modifier.height(8.dp))
        }
        VoiceHelpButton(onClick = { helpDlg = true })
        Spacer(Modifier.height(8.dp))
        VoiceMicButton(voice.on, onClick = { toggleVoice() })
    }
    }
    if (helpDlg) VoiceHelpDialog(onDismiss = { helpDlg = false }, listening = voice.on, onStart = { toggleVoice() })
    if (lockDlg) {
        val has = prefs.getString("pw", null) != null
        InputDialog(if (has) "Enter current password to REMOVE lock" else "Set new app password", "", { t ->
            if (has) {
                if (sha(t) == prefs.getString("pw", "")) { prefs.edit().remove("pw").apply(); Toast.makeText(c, "Lock removed", Toast.LENGTH_SHORT).show() }
                else Toast.makeText(c, "Wrong password", Toast.LENGTH_SHORT).show()
            } else { prefs.edit().putString("pw", sha(t)).apply(); Toast.makeText(c, "Lock set", Toast.LENGTH_SHORT).show() }
            lockDlg = false
        }, { lockDlg = false }, true)
    }
}

@Composable fun Home(onOpen: (String) -> Unit, onSearch: (String) -> Unit, onLock: () -> Unit, basketCount: Int = 0) {
    var q by remember { mutableStateOf("") }
    val cats = HOME_CATEGORIES
    Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Search files & folders") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) onSearch(q) }))
            TextButton(onLock) { Text("🔒", fontSize = 22.sp) }
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(cats) { idx, (i, n) ->
                Card(Modifier.fillMaxWidth().height(90.dp).clickable { onOpen(n) }) {
                    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) { Text(i, fontSize = 30.sp); Text("${idx + 1}. " + if (n == "Pick & Drop" && basketCount > 0) "$n ($basketCount)" else n) }
                }
            }
        }
    }
}

@Composable fun Organizer(all: List<File>, onChange: () -> Unit) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    val groups = remember(all) { all.filter { it.extension.isNotEmpty() }.groupBy { it.extension.lowercase() }.toList().sortedByDescending { it.second.size } }
    var pick by remember { mutableStateOf<String?>(null) }
    fun go(ext: String, move: Boolean) {
        val fs = groups.first { it.first == ext }.second; pick = null
        scope.launch {
            withContext(Dispatchers.IO) {
                val d = File(ROOT, "Organized/$ext").apply { mkdirs() }
                fs.forEach { if (!it.path.startsWith(d.path)) try { if (move) moveTo(it, d) else copyTo(it, d) } catch (_: Exception) {} }
            }
            Toast.makeText(c, "Done: Organized/$ext", Toast.LENGTH_SHORT).show(); onChange()
        }
    }
    Column {
        Text("File Organizer – tap an extension", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        LazyColumn { items(groups) { (ext, fs) ->
            Text(".$ext  —  ${fs.size} files • ${size(fs.sumOf { it.length() })}", Modifier.fillMaxWidth().clickable { pick = ext }.padding(16.dp))
            HorizontalDivider()
        } }
    }
    pick?.let { ext ->
        AlertDialog(onDismissRequest = { pick = null }, title = { Text("Organize .$ext files") },
            text = { Text("Creates folder Organized/$ext and puts all .$ext files in it.") },
            confirmButton = { TextButton({ go(ext, true) }) { Text("Move") } },
            dismissButton = { TextButton({ go(ext, false) }) { Text("Copy") } })
    }
}

@Composable fun InputDialog(title: String, init: String, onOk: (String) -> Unit, onDismiss: () -> Unit, secret: Boolean = false, spoken: String? = null) {
    var t by remember { mutableStateOf(init) }
    LaunchedEffect(spoken) { if (spoken != null) t = spoken }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(t, { t = it }, singleLine = true, visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None) },
        confirmButton = { TextButton({ if (t.isNotBlank()) onOk(t.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable fun FolderPicker(start: File, onPick: (File) -> Unit, onDismiss: () -> Unit) {
    var d by remember { mutableStateOf(start) }; var mk by remember { mutableStateOf(false) }; var r by remember { mutableIntStateOf(0) }
    val subs = remember(d, r) { d.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose folder (OK = use it)") },
        text = { Column { Text(d.path, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.height(300.dp)) {
                item { Text("⬆ ..", Modifier.fillMaxWidth().clickable { d.parentFile?.let { d = it } }.padding(10.dp)) }
                items(subs) { f -> Text("📁 ${f.name}", Modifier.fillMaxWidth().clickable { d = f }.padding(10.dp)) }
            } } },
        confirmButton = { TextButton({ onPick(d) }) { Text("OK") } },
        dismissButton = { Row { TextButton({ mk = true }) { Text("New folder") }; TextButton(onDismiss) { Text("Cancel") } } })
    if (mk) InputDialog("New folder", "", { n -> val nf = File(d, n.replace('/', '_')); nf.mkdirs(); mk = false; r++; if (nf.isDirectory) d = nf }, { mk = false })
}

@Composable fun DetailsDialog(files: List<File>, onDismiss: () -> Unit) {
    val txt by produceState("Loading…", files) {
        value = withContext(Dispatchers.IO) {
            files.joinToString("\n\n") { f ->
                if (f.isDirectory) {
                    val fs = f.walkTopDown().filter { it.isFile }.toList()
                    "Folder: ${f.name}\nFiles: ${fs.size}\nTotal size: ${size(fs.sumOf { it.length() })}\nPath: ${f.path}\nModified: ${date(f.lastModified())}"
                } else "Name: ${f.name}\nExtension: ${f.extension}\nSize: ${size(f.length())}\nPath: ${f.path}\nCreated: ${date(created(f))}\nModified: ${date(f.lastModified())}"
            }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Details") },
        text = { Text(txt, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onDismiss) { Text("Close") } })
}

fun emoji(f: File) = when {
    f.isDirectory -> "📁"; f.extension.lowercase() in TYPES["Audio"]!! -> "🎵"
    f.extension.lowercase() in setOf("zip") -> "🗜️"; else -> "📄"
}
fun isMedia(f: File) = f.extension.lowercase().let { it in TYPES["Images"]!! || it in TYPES["Videos"]!! }

@Composable fun rememberSub(fi: FI): String {
    val s by produceState("", fi.f) {
        value = withContext(Dispatchers.IO) {
            val f = fi.f
            if (f.isDirectory) "Folder • ${date(f.lastModified())}" else "${size(f.length())} • ${date(created(f))}"
        }
    }
    return s
}

@Composable fun RowItem(fi: FI, sel: Boolean, selMode: Boolean, fav: Boolean, loader: ImageLoader, onClick: () -> Unit, onLong: () -> Unit, picked: Boolean = false, index: Int = 0) {
    Row(Modifier.fillMaxWidth().background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        .combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selMode) Checkbox(sel, { onClick() })
        if (index > 0) Text("$index", Modifier.width(30.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, maxLines = 1)
        if (isMedia(fi.f)) AsyncImage(model = fi.f, contentDescription = null, imageLoader = loader, modifier = Modifier.size(48.dp), contentScale = ContentScale.Crop)
        else Box(Modifier.size(48.dp), Alignment.Center) { Text(emoji(fi.f), fontSize = 28.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(fi.f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(rememberSub(fi), style = MaterialTheme.typography.bodySmall)
        }
        if (fav) Text("★")
        if (picked) Text("🧺")
    }
}

@Composable fun Browser(kind: String, all: List<File>, dirs: List<File>, favs: List<String>, ver: Int, onChange: () -> Unit,
                        setFav: (List<File>, Boolean) -> Unit, startDir: File?, initialQuery: String, basket: Basket, voice: VoiceHost, onHome: () -> Unit) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    val start = remember { when (kind) { "Storage" -> startDir ?: ROOT; "Backup" -> File(ROOT, "Backup").apply { mkdirs() }; else -> null } }
    var cur by remember { mutableStateOf(start) }
    var q by remember { mutableStateOf(initialQuery) }
    var sort by remember { mutableStateOf("A-Z") }
    var docExt by remember { mutableStateOf<String?>(null) }
    var hidden by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dlg by remember { mutableStateOf("") }
    var rev by remember { mutableIntStateOf(0) }
    var selMode by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(true) }
    var favTab by remember { mutableStateOf("Folders") }
    var scDlg by remember { mutableStateOf<File?>(null) }
    var zipDlg by remember { mutableStateOf<File?>(null) }
    var rows by remember { mutableStateOf(emptyList<FI>()) }
    val sel = remember { mutableStateListOf<File>() }
    val loader = remember { ImageLoader.Builder(c).components { add(VideoFrameDecoder.Factory()) }.build() }

    BackHandler(selMode || (cur != null && cur != start)) {
        if (selMode) { selMode = false; sel.clear() } else cur = if (start == null) null else cur!!.parentFile
    }
    LaunchedEffect(cur, ver, rev, hidden, q, sort, docExt, favTab, favs.size) {
        val d = cur; val qq = q; val so = sort; val de = docExt; val hid = hidden; val ft = favTab; val fl0 = favs.toList()
        if (qq.isNotEmpty()) delay(150)
        rows = withContext(Dispatchers.IO) {
            val base: List<File> = when {
                d != null -> d.listFiles()?.filter { hid || !it.name.startsWith(".") } ?: emptyList()
                kind == "Favourites" -> fl0.map { File(it) }.filter { it.exists() && it.isDirectory == (ft == "Folders") }
                kind == "Search" -> dirs + all
                else -> { val ex = if (de != null) setOf(de) else TYPES[kind] ?: emptySet(); all.filter { it.extension.lowercase() in ex } }
            }
            val fl = if (qq.isBlank()) base else base.filter { it.name.contains(qq, true) }
            fl.map { FI(it, it.isDirectory, if (so == "Largest") it.length() else 0, if (so == "Newest") it.lastModified() else 0) }
                .sortedWith(compareBy<FI> { !it.dir }.thenComparator { a, b ->
                    when (so) { "A-Z" -> a.f.name.compareTo(b.f.name, true); "Z-A" -> b.f.name.compareTo(a.f.name, true)
                        "Newest" -> b.mod.compareTo(a.mod); else -> b.len.compareTo(a.len) } })
        }
    }
    fun run(msg: String, job: () -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { try { job() } catch (e: Exception) {} }
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show(); voice.speak(msg); sel.clear(); selMode = false; rev++; onChange()
    }
    fun pick() { val n = basket.add(sel.toList()); Toast.makeText(c, "🧺 $n picked • ${basket.items.size} in basket", Toast.LENGTH_SHORT).show(); sel.clear(); selMode = false }
    fun dropHere(d: File, move: Boolean) { scope.dropBasket(c, basket, d, move) { rev++; onChange() } }
    fun tog(f: File) { if (!sel.remove(f)) sel.add(f) }
    fun click(f: File) { if (selMode) tog(f) else if (f.isDirectory) cur = f else openFile(c, f) }
    fun longp(f: File) { selMode = true; if (f !in sel) sel.add(f) }
    fun act(f: () -> Unit): () -> Unit = { menu = false; f() }
    fun zipSel() { val fs = sel.toList(); val par = cur ?: fs[0].parentFile!!; run("Zipped") { zip(fs, uniq(par, "archive.zip")) } }
    val dir = cur ?: ROOT
    val allFav = sel.isNotEmpty() && sel.all { it.path in favs }

    var spoken by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var showDrop by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(dlg) { spoken = null }
    // Like the Voice File Manager: tick files and the app asks, out loud, what to do.
    LaunchedEffect(sel.size) {
        if (sel.isNotEmpty()) {
            delay(1500)
            if (!voice.on && voice.autoAsk && hasMicPermission(c)) voice.start()
            else voice.askSelected(sel.size)
        }
    }
    fun vtoast(m: String) { Toast.makeText(c, m, Toast.LENGTH_SHORT).show(); voice.speak(m) }
    fun clip(t: String) { (c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("path", t)) }
    fun vsel(nums: List<Int>): Boolean {
        val fs = nums.mapNotNull { rows.getOrNull(it - 1)?.f }
        if (fs.isEmpty()) { vtoast("No item with that number"); return false }
        selMode = true
        fs.forEach { if (it !in sel) sel.add(it) }
        return true
    }
    fun openDrop() {
        val d = cur
        if (basket.items.isEmpty()) {
            vtoast("Basket is empty — select items and say “copy” or “move”")
        } else if (d == null) {
            vtoast("Open a folder first")
        } else {
            chosen = if (basket.items.size == 1) setOf(basket.items[0].path) else emptySet()
            showDrop = true
            vtoast("Say the numbers to drop (any order) or “all”, then “drop”")
        }
    }
    fun runDrop() {
        val d = cur ?: return
        val list = basket.items.filter { it.path in chosen }
        if (list.isEmpty()) { vtoast("Choose items first — say numbers or “all”"); return }
        showDrop = false
        voice.quickPaste = false
        val mv = voice.dropMove
        scope.launch {
            val (done, renamed) = withContext(Dispatchers.IO) { dropAll(list, d, mv) }
            if (mv) basket.items.removeAll(done)
            vtoast((if (mv) "Moved " else "Copied ") + "${done.size} to ${d.name}" + (if (renamed > 0) " • $renamed same-name → date/time added" else ""))
            rev++; onChange()
        }
    }
    fun pasteNow() {
        if (basket.items.isEmpty()) {
            vtoast("Nothing to paste. Select items and say copy or move first")
        } else if (cur == null) {
            vtoast("Open a folder first")
        } else {
            chosen = basket.items.map { it.path }.toSet()
            runDrop()
        }
    }
    fun dropVoice(cmd: VCmd) {
        val all = basket.items.toList()
        fun choose(nums: List<Int>) { nums.forEach { n -> all.getOrNull(n - 1)?.let { chosen = chosen + it.path } } }
        when (cmd) {
            is VCmd.Num -> {
                val f = all.getOrNull(cmd.n - 1)
                if (f == null) vtoast("No picked item ${cmd.n}")
                else chosen = if (f.path in chosen) chosen - f.path else chosen + f.path
            }
            is VCmd.Select -> choose(cmd.nums)
            is VCmd.Unselect -> cmd.nums.forEach { n -> all.getOrNull(n - 1)?.let { chosen = chosen - it.path } }
            is VCmd.SelectAll -> chosen = all.map { it.path }.toSet()
            is VCmd.ClearSelection -> chosen = emptySet()
            is VCmd.Do -> {
                if (cmd.verb == Verb.COPY || cmd.verb == Verb.MOVE || cmd.verb == Verb.DROP || cmd.verb == Verb.PICKUP) {
                    if (cmd.verb == Verb.COPY) voice.dropMove = false
                    if (cmd.verb == Verb.MOVE) voice.dropMove = true
                    if (cmd.arg == "all") chosen = all.map { it.path }.toSet()
                    choose(cmd.nums)
                    if (cmd.verb == Verb.DROP) runDrop() else vtoast(if (voice.dropMove) "Mode: move" else "Mode: copy")
                } else {
                    vtoast("Say numbers, “move” or “copy”, then “drop”")
                }
            }
            is VCmd.Yes -> runDrop()
            is VCmd.No -> showDrop = false
            is VCmd.NewItem -> {
                val base = cur
                if (base != null && cmd.folder && cmd.name.isNotBlank()) {
                    val nf = File(base, cmd.name)
                    if (nf.isDirectory || nf.mkdirs()) { cur = nf; vtoast("Dropping into “${nf.name}” — say “drop”") }
                    else vtoast("Could not create that folder")
                } else {
                    vtoast("Say: new folder, then the name")
                }
            }
            is VCmd.Stop -> voice.stop(true)
            is VCmd.Help -> showHelp = true
            else -> vtoast("Say numbers (any order) or “all”, “move” or “copy”, then “drop”")
        }
    }
    fun openVoiceItem(f: File) { selMode = false; sel.clear(); if (f.isDirectory) cur = f else openFile(c, f) }
    fun voiceVerb(cmd: VCmd.Do) {
        if (cmd.verb == Verb.PASTE) { pasteNow(); return }
        if (cmd.verb == Verb.DROP) {
            openDrop()
            if (showDrop && (cmd.arg == "all" || cmd.nums.isNotEmpty())) {
                val all = basket.items.toList()
                chosen = if (cmd.arg == "all") all.map { it.path }.toSet()
                    else cmd.nums.mapNotNull { all.getOrNull(it - 1)?.path }.toSet()
                runDrop()
            }
            return
        }
        if (cmd.arg == "all") { selMode = true; sel.clear(); sel.addAll(rows.map { it.f }) }
        else if (cmd.nums.isNotEmpty()) { sel.clear(); if (!vsel(cmd.nums)) return }
        val nameArg = cmd.arg
        if (cmd.verb == Verb.OPEN && nameArg != null && nameArg != "all" && cmd.nums.isEmpty()) {
            val hit = rows.firstOrNull { it.f.name.contains(nameArg, true) }
            if (hit == null) vtoast("No item named “$nameArg” here") else openVoiceItem(hit.f)
            return
        }
        var fs = sel.toList()
        if (fs.isEmpty()) {
            if (cmd.verb == Verb.COPYPATH) { clip(dir.path); vtoast("Folder path copied"); return }
            if (cmd.verb == Verb.BACKUP && cur != null && dir != ROOT) fs = listOf(dir)
            if (fs.isEmpty()) { vtoast("Select items first — say “select 2 4”, or add numbers like “copy 3”"); return }
        }
        when (cmd.verb) {
            Verb.OPEN -> { if (fs.size == 1) openVoiceItem(fs[0]) else vtoast("Say one number to open, like “open 5”") }
            Verb.COPY, Verb.MOVE, Verb.PICKUP -> {
                if (cmd.verb != Verb.PICKUP) voice.dropMove = cmd.verb == Verb.MOVE
                voice.quickPaste = cmd.verb != Verb.PICKUP
                basket.add(fs)
                vtoast(if (cmd.verb == Verb.PICKUP) "🧺 ${fs.size} picked up — open a folder and say “drop”"
                    else "🧺 ${fs.size} ready to ${if (voice.dropMove) "move" else "copy"}. Say a folder number to paste into it, or open a folder and say paste")
                sel.clear(); selMode = false
            }
            Verb.DROP -> openDrop()
            Verb.PASTE -> pasteNow()
            Verb.RENAME -> {
                if (fs.size != 1) {
                    vtoast("Select just one item to rename")
                } else if (nameArg != null && nameArg != "all") {
                    val f = fs[0]
                    val nm = keepExt(f, nameArg)
                    sel.clear(); selMode = false
                    run("Renamed") { f.renameTo(File(f.parentFile, nm)) }
                } else {
                    sel.clear(); sel.addAll(fs); spoken = null; dlg = "rename"
                    vtoast("Say the new name, then “confirm”")
                }
            }
            Verb.DELETE -> { sel.clear(); sel.addAll(fs); dlg = "delete"; vtoast("Say “yes” to delete ${fs.size} item(s), or “cancel”") }
            Verb.BOOKMARK -> { setFav(fs, !fs.all { it.path in favs }); sel.clear(); selMode = false; rev++ }
            Verb.ZIP -> { sel.clear(); sel.addAll(fs); zipSel() }
            Verb.UNZIP -> {
                val zs = fs.filter { it.isFile && it.extension.equals("zip", true) }
                if (zs.isEmpty()) vtoast("No zip file selected")
                else run("Extracted") { zs.forEach { unzip(it, uniq(it.parentFile!!, it.nameWithoutExtension)) } }
            }
            Verb.BACKUP -> { run("Backed up to the Backup folder") { val d = File(ROOT, "Backup").apply { mkdirs() }; fs.forEach { copyTo(it, d) } } }
            Verb.SHARE -> {
                if (fs.none { it.isFile }) vtoast("Folders can’t be shared — say “zip” first")
                else { try { share(c, fs) } catch (e: Exception) { vtoast("Nothing can share this") }; sel.clear(); selMode = false }
            }
            Verb.COPYPATH -> { clip(fs.joinToString("\n") { it.path }); vtoast("Path copied"); sel.clear(); selMode = false }
            Verb.DETAILS -> { sel.clear(); sel.addAll(fs); dlg = "details" }
        }
    }
    fun runVoice(ev: VEvent) {
        if (ev.handled) return
        ev.handled = true
        val cmd = ev.cmd
        if (showDrop) { dropVoice(cmd); return }
        val naming = dlg == "rename" || dlg == "newfolder" || dlg == "newfile"
        if (naming && cmd !is VCmd.Yes && cmd !is VCmd.No && cmd !is VCmd.Stop) {
            val nm = spokenName(ev.heard)
            if (nm.isNotBlank()) {
                val v = if (dlg == "rename" && sel.size == 1) keepExt(sel[0], nm) else nm
                spoken = v
                vtoast("Name: $v — say “confirm” or “cancel”")
            }
            return
        }
        val overlay = (dlg.isNotEmpty() && !naming) || zipDlg != null || scDlg != null
        if (overlay && cmd !is VCmd.Yes && cmd !is VCmd.No && cmd !is VCmd.Stop && cmd !is VCmd.Help) {
            vtoast("Say “cancel” to close this first")
            return
        }
        when (cmd) {
            is VCmd.Stop -> voice.stop(true)
            is VCmd.Help -> showHelp = true
            is VCmd.Yes -> {
                val t = spoken
                if (dlg == "delete" && sel.isNotEmpty()) {
                    val fs = sel.toList(); dlg = ""
                    run("Deleted") { fs.forEach { it.deleteRecursively() } }
                } else if (dlg == "rename" && sel.size == 1) {
                    if (t.isNullOrBlank()) vtoast("Say the new name first")
                    else { val f = sel[0]; dlg = ""; run("Renamed") { f.renameTo(File(f.parentFile, t)) } }
                } else if (dlg == "newfolder") {
                    if (t.isNullOrBlank()) vtoast("Say the folder name first")
                    else { dlg = ""; run("Created") { File(dir, t).mkdirs() } }
                } else if (dlg == "newfile") {
                    if (t.isNullOrBlank()) vtoast("Say the file name first")
                    else { dlg = ""; run("Created") { File(dir, if (t.contains('.')) t else "$t.txt").createNewFile() } }
                } else if (dlg.isNotEmpty()) {
                    dlg = ""
                } else {
                    vtoast("Nothing to confirm")
                }
            }
            is VCmd.No -> {
                if (dlg.isNotEmpty()) dlg = ""
                else if (zipDlg != null) zipDlg = null
                else if (scDlg != null) scDlg = null
                else if (showHelp) showHelp = false
                else if (selMode) { selMode = false; sel.clear() }
                else vtoast("Nothing to cancel")
            }
            is VCmd.Back -> {
                if (selMode) { selMode = false; sel.clear() }
                else if (cur != null && cur != start) { cur = if (start == null) null else cur!!.parentFile }
                else { onHome() }
            }
            is VCmd.Home -> onHome()
            is VCmd.Refresh -> { rev++; onChange() }
            is VCmd.Search -> { q = cmd.q; vtoast("Searching: ${cmd.q}") }
            is VCmd.ClearSearch -> q = ""
            is VCmd.SelectAll -> { selMode = true; sel.clear(); sel.addAll(rows.map { it.f }) }
            is VCmd.ClearSelection -> { selMode = false; sel.clear() }
            is VCmd.Select -> {
                if (cmd.nums.isEmpty()) { selMode = true; vtoast("Selection on — say the serial numbers") }
                else if (vsel(cmd.nums)) vtoast("${sel.size} selected")
            }
            is VCmd.Unselect -> cmd.nums.forEach { n -> rows.getOrNull(n - 1)?.f?.let { sel.remove(it) } }
            is VCmd.SelectMatch -> {
                val hits = rows.filter { matchPredicate(cmd.q)(it.f) }
                if (hits.isEmpty()) {
                    vtoast("No items match ${cmd.q}")
                } else {
                    selMode = true
                    hits.forEach { if (it.f !in sel) sel.add(it.f) }
                    vtoast("Selected ${hits.size}")
                }
            }
            is VCmd.Num -> {
                val f = rows.getOrNull(cmd.n - 1)?.f
                if (f == null) vtoast("There is no item number ${cmd.n}")
                else if (f.isDirectory) {
                    openVoiceItem(f)
                    if (voice.quickPaste && basket.items.isNotEmpty()) pasteNow()
                }
                else { selMode = true; tog(f) }
            }
            is VCmd.NewItem -> {
                if (cur == null) {
                    vtoast("Open a folder first")
                } else if (cmd.name.isNotBlank()) {
                    val n = cmd.name
                    run("Created") { if (cmd.folder) File(dir, n).mkdirs() else File(dir, if (n.contains('.')) n else "$n.txt").createNewFile() }
                } else {
                    spoken = null
                    dlg = if (cmd.folder) "newfolder" else "newfile"
                    vtoast("Say the name, then “confirm”")
                }
            }
            is VCmd.Do -> voiceVerb(cmd)
            is VCmd.Text -> vtoast("Didn’t understand “${cmd.s}” — say “help”")
        }
    }
    LaunchedEffect(voice.event) { voice.event?.let { runVoice(it) } }

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (selMode) "${sel.size} selected" else cur?.let { if (it == ROOT) "Phone storage" else it.name } ?: kind, maxLines = 1) }, actions = {
            TextButton({ selMode = !selMode; if (!selMode) sel.clear() }) { Text(if (selMode) "✕" else "☑", fontSize = 20.sp) }
            Box {
                TextButton({ menu = true }) { Text("⋮", fontSize = 22.sp) }
                DropdownMenu(menu, { menu = false }) {
                    if (selMode) {
                        Mi("Select all", act { sel.clear(); sel.addAll(rows.map { it.f }) })
                        if (sel.isNotEmpty()) {
                            val one = sel.size == 1
                            Mi("Share", act { share(c, sel.toList()) })
                            if (one) Mi("Rename", act { dlg = "rename" })
                            Mi("Copy file path", act { (c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("path", sel.joinToString("\n") { it.path })); sel.clear() })
                            if (one && sel[0].extension.lowercase() == "zip") Mi("Decompress", act { val f = sel[0]; run("Extracted") { unzip(f, uniq(f.parentFile!!, f.nameWithoutExtension)) } })
                            if (one && isZip(sel[0])) Mi("View zip contents", act { val z = sel[0]; sel.clear(); selMode = false; zipDlg = z })
                            if (one && sel[0].isFile) Mi("Open with", act { openFile(c, sel[0]); sel.clear() })
                            if (one) Mi("Create shortcut", act { scDlg = sel[0] })
                            Mi("Hide", act { val fs = sel.toList(); run("Hidden") { fs.forEach { it.renameTo(File(it.parentFile, "." + it.name)) } } })
                            Mi(if (allFav) "Remove bookmark" else "Add bookmark", act { setFav(sel.toList(), !allFav); sel.clear(); selMode = false; rev++ })
                            Mi("Backup", act { val fs = sel.toList(); run("Backed up") { val d = File(ROOT, "Backup").apply { mkdirs() }; fs.forEach { copyTo(it, d) } } })
                            Mi("Details", act { dlg = "details" })
                        }
                    } else {
                        listOf("A-Z", "Z-A", "Newest", "Largest").forEach { s -> Mi("Sort: $s" + if (s == sort) " ✓" else "", act { sort = s }) }
                        if (kind == "Images" || kind == "Videos") Mi(if (gridView) "List view" else "Grid view", act { gridView = !gridView })
                        if (cur != null) {
                            Mi("New folder", act { dlg = "newfolder" }); Mi("New file", act { dlg = "newfile" })
                            Mi("Folder details", act { dlg = "folderdetails" })
                            Mi("Export list to .txt", act {
                                val d = cur!!
                                run("Saved ${d.name}_list.txt") {
                                    val ls = (d.listFiles()?.filter { hidden || !it.name.startsWith(".") } ?: emptyList())
                                        .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                                    File(d, "${d.name}_list.txt").writeText("Folder: ${d.name}\nPath: ${d.path}\nItems: ${ls.size}\n\n" +
                                        ls.mapIndexed { i, x -> "${i + 1}. " + x.name + (if (x.isDirectory) "/" else "") }.joinToString("\n"))
                                }
                            })
                            Mi((if (hidden) "Hide" else "Show") + " hidden files", act { hidden = !hidden })
                        }
                    }
                }
            }
        })
    }, bottomBar = {
        if (selMode) BottomAppBar {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                val on = sel.isNotEmpty()
                TextButton({ dlg = "copy" }, enabled = on) { Text("Copy") }
                TextButton({ dlg = "move" }, enabled = on) { Text("Move") }
                TextButton({ zipSel() }, enabled = on) { Text("Zip") }
                TextButton({ pick() }, enabled = on) { Text("Pick") }
                TextButton({ dlg = "delete" }, enabled = on) { Text("Delete") }
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 8.dp), placeholder = { Text("Search") }, singleLine = true)
            if (basket.items.isNotEmpty()) BasketStrip(basket, cur != null, { mv -> dropHere(cur!!, mv) }, { dlg = "newfolderdrop" }, { dlg = "basket" })
            if (kind == "Documents") LazyRow(Modifier.padding(horizontal = 8.dp)) {
                item { FilterChip(docExt == null, { docExt = null }, { Text("All") }) }
                items(DOCX) { e -> Spacer(Modifier.width(6.dp)); FilterChip(docExt == e, { docExt = e }, { Text(e) }) }
            }
            if (kind == "Favourites" && cur == null) Row(Modifier.padding(horizontal = 8.dp)) {
                listOf("Folders", "Files").forEach { t -> FilterChip(favTab == t, { favTab = t }, { Text(t) }); Spacer(Modifier.width(6.dp)) }
            }
            if ((kind == "Images" || kind == "Videos") && gridView) LazyVerticalGrid(GridCells.Adaptive(100.dp), contentPadding = PaddingValues(4.dp)) {
                itemsIndexed(rows, key = { _, fi -> fi.f.path }) { vi, fi ->
                    Box(Modifier.padding(2.dp).aspectRatio(1f).combinedClickable(onClick = { click(fi.f) }, onLongClick = { longp(fi.f) })
                        .then(if (fi.f in sel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier)) {
                        AsyncImage(model = fi.f, contentDescription = fi.f.name, imageLoader = loader, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (selMode) Checkbox(fi.f in sel, { click(fi.f) }, Modifier.align(Alignment.TopStart))
                        if (fi.f in basket.items) Text("🧺", Modifier.align(Alignment.TopEnd).padding(2.dp))
                        Text("${vi + 1}", Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 4.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else LazyColumn {
                itemsIndexed(rows, key = { _, fi -> fi.f.path }) { vi, fi ->
                    RowItem(fi, fi.f in sel, selMode, fi.f.path in favs, loader, { click(fi.f) }, { longp(fi.f) }, picked = fi.f in basket.items, index = vi + 1)
                }
            }
        }
    }
    when (dlg) {
        "copy", "move" -> { val m = dlg; FolderPicker(dir, { d -> val fs = sel.toList(); dlg = ""; run("Done") { fs.forEach { if (m == "copy") copyTo(it, d) else moveTo(it, d) } } }, { dlg = "" }) }
        "rename" -> InputDialog("Rename", sel[0].name, { n -> val f = sel[0]; dlg = ""; run("Renamed") { f.renameTo(File(f.parentFile, n)) } }, { dlg = "" }, spoken = spoken)
        "newfolder" -> InputDialog("New folder", "", { n -> dlg = ""; run("Created") { File(dir, n).mkdirs() } }, { dlg = "" }, spoken = spoken)
        "newfile" -> InputDialog("New file", "new.txt", { n -> dlg = ""; run("Created") { File(dir, n).createNewFile() } }, { dlg = "" }, spoken = spoken)
        "basket" -> BasketDialog(basket, { rev++; onChange() }, { dlg = "" })
        "newfolderdrop" -> NewFolderDropDialog(dir, { d, mv -> dlg = ""; dropHere(d, mv) }, { dlg = "" })
        "details" -> DetailsDialog(sel.toList()) { dlg = "" }
        "folderdetails" -> DetailsDialog(listOf(dir)) { dlg = "" }
        "delete" -> AlertDialog(onDismissRequest = { dlg = "" }, title = { Text("Delete ${sel.size} item(s)?") },
            confirmButton = { TextButton({ val fs = sel.toList(); dlg = ""; run("Deleted") { fs.forEach { it.deleteRecursively() } } }) { Text("Delete") } },
            dismissButton = { TextButton({ dlg = "" }) { Text("Cancel") } })
    }
    if (showHelp) VoiceHelpDialog(onDismiss = { showHelp = false })
    if (showDrop) {
        DropPanel(
            dest = cur?.name ?: "this folder",
            items = basket.items.toList(),
            chosen = chosen,
            move = voice.dropMove,
            onToggle = { f -> chosen = if (f.path in chosen) chosen - f.path else chosen + f.path },
            onMode = { voice.dropMove = it },
            onDrop = { runDrop() },
            onCancel = { showDrop = false }
        )
    }
    scDlg?.let { ShortcutDialog(it) { scDlg = null } }
    zipDlg?.let { ZipViewerDialog(it, basket, { zipDlg = null }) { rev++; onChange() } }
}

@Composable fun Mi(t: String, f: () -> Unit) = DropdownMenuItem(text = { Text(t) }, onClick = f)
