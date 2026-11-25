@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.files

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Files picked from any folders; lives in App() so it survives navigation between folders/screens. */
class Basket {
    val items = mutableStateListOf<File>()
    /** Adds files not already in the basket; returns how many were new. */
    fun add(fs: List<File>): Int {
        var n = 0
        fs.forEach { f -> if (items.none { it.path == f.path }) { items.add(f); n++ } }
        return n
    }
}

/** Moves/copies each item into [dir]. Returns the items actually dropped, and how many got a date/time name because the name was taken. */
fun dropAll(items: List<File>, dir: File, move: Boolean): Pair<List<File>, Int> {
    var renamed = 0
    dir.mkdirs()
    val target = dir.canonicalPath
    val done = mutableListOf<File>()
    for (f in items) {
        try {
            if (!f.exists()) continue
            val src = f.canonicalPath
            if (move && f.parentFile?.canonicalPath == target) continue          // already there
            if (f.isDirectory && (target == src || target.startsWith(src + File.separator))) continue // folder into itself
            if (File(dir, f.name).exists()) renamed++
            if (move) moveTo(f, dir) else copyTo(f, dir)
            done.add(f)
        } catch (_: Exception) {}
    }
    return done to renamed
}

fun CoroutineScope.dropBasket(c: Context, basket: Basket, dir: File, move: Boolean, onDone: () -> Unit) = launch {
    val items = basket.items.toList()
    val (done, renamed) = withContext(Dispatchers.IO) { dropAll(items, dir, move) }
    if (move) basket.items.removeAll(done)          // moved files leave the basket; copies stay so you can drop them elsewhere too
    val skipped = items.size - done.size
    Toast.makeText(c, (if (move) "Moved " else "Copied ") + "${done.size} to ${dir.name}" + (if (renamed > 0) " • $renamed same-name → date/time added to name" else "") + (if (skipped > 0) " • $skipped skipped" else ""), Toast.LENGTH_LONG).show()
    onDone()
}

@Composable fun BasketList(basket: Basket, modifier: Modifier = Modifier, onZip: ((File) -> Unit)? = null) {
    if (basket.items.isEmpty()) Text("Basket is empty. Select files (☑), then tap Pick.", modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
    else LazyColumn(modifier) {
        items(basket.items.toList(), key = { it.path }) { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(emoji(f) + " " + f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(f.parent ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (onZip != null && isZip(f)) TextButton({ onZip(f) }) { Text("📂") }
                TextButton({ basket.items.remove(f) }) { Text("✕") }
            }
            HorizontalDivider()
        }
    }
}

/** Banner shown inside any browser screen while the basket has files. */
@Composable fun BasketStrip(basket: Basket, canDropHere: Boolean, onDropHere: (Boolean) -> Unit, onNewFolder: () -> Unit, onView: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧺 ${basket.items.size} picked", Modifier.weight(1f).clickable { onView() }, style = MaterialTheme.typography.titleSmall)
                TextButton(onView) { Text("Menu") }
                TextButton({ basket.items.clear() }) { Text("Clear") }
            }
            if (canDropHere) {
                Row {
                    Button({ onDropHere(true) }) { Text("Move here") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton({ onDropHere(false) }) { Text("Copy here") }
                }
                TextButton(onNewFolder) { Text("📁 Drop into a NEW folder…") }
            } else Text("Open a folder (Storage) to drop here, or tap Menu → Move/Copy/Zip…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Create a folder inside [parent] and Move or Copy the basket into it in one step. */
@Composable fun NewFolderDropDialog(parent: File, onDrop: (File, Boolean) -> Unit, onDismiss: () -> Unit) {
    var n by remember { mutableStateOf("New folder") }
    fun go(mv: Boolean) {
        val nm = n.trim().replace('/', '_'); if (nm.isBlank()) return
        val d = File(parent, nm); d.mkdirs(); onDrop(d, mv)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Drop into new folder") },
        text = { Column {
            Text("Created inside ${parent.name}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(n, { n = it }, singleLine = true)
        } },
        confirmButton = { TextButton({ go(true) }) { Text("Move") } },
        dismissButton = { Row { TextButton({ go(false) }) { Text("Copy") }; TextButton(onDismiss) { Text("Cancel") } } })
}

/**
 * Everything you can do with the picked files, wherever they came from:
 * Move / Copy, Zip, Unzip, add to an existing zip (merge), open a zip to see & extract its files.
 */
@Composable fun BasketPanel(basket: Basket, onChange: () -> Unit, modifier: Modifier = Modifier, fixedList: Boolean = false) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("") }
    var zipName by remember { mutableStateOf("archive") }
    var viewZip by remember { mutableStateOf<File?>(null) }
    val has = basket.items.isNotEmpty()
    val zips = basket.items.filter { isZip(it) }
    fun job(msg: () -> String) = scope.bg(c, onChange, msg)
    fun nm() = zipName.trim().replace('/', '_').removeSuffix(".zip").ifBlank { "archive" }

    Column(modifier) {
        Row {
            Button({ mode = "folder:move" }, enabled = has) { Text("Move to…") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton({ mode = "folder:copy" }, enabled = has) { Text("Copy to…") }
        }
        Row(Modifier.padding(top = 4.dp)) {
            FilledTonalButton({ mode = "zipMenu" }, enabled = has, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("🗜 Zip…") }
            Spacer(Modifier.width(6.dp))
            FilledTonalButton({
                if (zips.isEmpty()) Toast.makeText(c, "No .zip files in the basket", Toast.LENGTH_SHORT).show() else mode = "unzipMenu"
            }, enabled = has, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("📂 Unzip…") }
            Spacer(Modifier.width(6.dp))
            FilledTonalButton({ mode = "mergePick" }, enabled = has, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("➕ To zip…") }
        }
        Text("${basket.items.size} item(s) • tap 📂 on a zip to see its files", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
        BasketList(basket, if (fixedList) Modifier.height(240.dp) else Modifier.weight(1f)) { viewZip = it }
    }

    if (mode.startsWith("folder:")) {
        val kind = mode.removePrefix("folder:")
        FolderPicker(ROOT, { d ->
            mode = ""
            val items = basket.items.toList(); val name = nm()
            when (kind) {
                "move" -> scope.dropBasket(c, basket, d, true, onChange)
                "copy" -> scope.dropBasket(c, basket, d, false, onChange)
                "zip" -> job { val out = uniq(d, "$name.zip"); "Zipped ${zipMany(items, out)} file(s) → ${out.name}" }
                "unzip" -> job {
                    var n = 0
                    items.filter { isZip(it) }.forEach { n += extractZip(it, null, uniq(d, it.nameWithoutExtension)) }
                    "Unzipped $n file(s) into ${d.name}"
                }
                else -> {}
            }
        }, { mode = "" })
    }
    if (mode == "zipMenu") AlertDialog(onDismissRequest = { mode = "" }, title = { Text("Zip ${basket.items.size} picked item(s)") },
        text = { Column {
            OutlinedTextField(zipName, { zipName = it }, label = { Text("Archive name") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            Button({
                mode = ""
                val items = basket.items.toList(); val name = nm()
                job {
                    var z = 0
                    for ((p, fs) in items.groupBy { it.parentFile }) if (p != null) { zipMany(fs, uniq(p, "$name.zip")); z++ }
                    "Created $z zip file(s), each in its own folder"
                }
            }, Modifier.fillMaxWidth()) { Text("Each in its own folder") }
            Text("One zip per source folder, saved there", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ mode = "folder:zip" }, Modifier.fillMaxWidth()) { Text("All into one folder…") }
            Text("One zip with everything; choose or create the folder", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton({ mode = "" }) { Text("Cancel") } })
    if (mode == "unzipMenu") AlertDialog(onDismissRequest = { mode = "" }, title = { Text("Unzip ${zips.size} zip file(s)") },
        text = { Column {
            Button({
                mode = ""
                val zs = zips.toList()
                job {
                    var n = 0
                    zs.forEach { n += extractZip(it, null, uniq(it.parentFile!!, it.nameWithoutExtension)) }
                    "Unzipped $n file(s), each into its own folder"
                }
            }, Modifier.fillMaxWidth()) { Text("Each in its own folder") }
            Text("New folder named after the zip, next to it", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ mode = "folder:unzip" }, Modifier.fillMaxWidth()) { Text("All into one folder…") }
            Text("Choose or create the folder", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton({ mode = "" }) { Text("Cancel") } })
    if (mode == "mergePick") ZipPicker({ z ->
        mode = ""
        val items = basket.items.toList()
        job {
            val (a, u) = mergeZip(z, items)
            "Added $a file(s) to ${z.name}" + if (u > 0) " • $u already existed → added with date/time in name" else ""
        }
    }, { mode = "" })
    viewZip?.let { ZipViewerDialog(it, basket, { viewZip = null }, onChange) }
}

/** Popup version of the basket, opened from the banner inside any browser screen. */
@Composable fun BasketDialog(basket: Basket, onChange: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("🧺 Picked files (${basket.items.size})") },
        text = { BasketPanel(basket, onChange, Modifier, true) },
        confirmButton = { TextButton(onDismiss) { Text("Close") } })
}

/** Home → "Pick & Drop": dedicated screen to manage the basket and work on it. */
@Composable fun PickDropScreen(basket: Basket, onBrowse: () -> Unit, onChange: () -> Unit) {
    Column(Modifier.padding(12.dp)) {
        Text("🧺 Pick & Drop", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("1. Browse any folder, tick ☑ files/folders, tap Pick.\n2. Visit other folders and pick more.\n3. Drop with Move/Copy, or Zip/Unzip them all at once – in their own folders or one folder you choose (you can create it).",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.padding(vertical = 8.dp)) {
            Button(onBrowse) { Text("Browse & pick") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton({ basket.items.clear() }, enabled = basket.items.isNotEmpty()) { Text("Clear") }
        }
        BasketPanel(basket, onChange, Modifier.weight(1f), false)
    }
}
