@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.files

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/* ---------------------------------------------------------------- logic */

class ZEntry(val name: String, val size: Long, val time: Long, val dir: Boolean)

/** File-name-safe date+time, e.g. 2026-10-30 11-05-22 */
fun stamp(t: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.getDefault()).format(Date(t))
fun dt(t: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(t))
fun isZip(f: File) = f.isFile && f.extension.equals("zip", true)

/** Runs [job] off the main thread, toasts its result message, then calls [onDone]. */
fun CoroutineScope.bg(c: Context, onDone: () -> Unit, job: () -> String) = launch {
    val msg = withContext(Dispatchers.IO) { try { job() } catch (e: Exception) { "Failed: ${e.message}" } }
    Toast.makeText(c, msg, Toast.LENGTH_LONG).show(); onDone()
}

/** Returns [name] if free, otherwise a variant. tag != null -> "name (updated <tag>).ext", else "name (1).ext". */
private fun uniqueEntry(name: String, used: MutableSet<String>, tag: String?): String {
    if (used.add(name)) return name
    val s = name.lastIndexOf('/') + 1
    val dir = name.substring(0, s); val base = name.substring(s)
    val dot = base.lastIndexOf('.')
    val stem = if (dot > 0) base.substring(0, dot) else base
    val ext = if (dot > 0) base.substring(dot) else ""
    for (n in 0 until 100000) {
        val label = if (tag != null) "updated $tag" + (if (n > 0) " ${n + 1}" else "") else "${n + 1}"
        val cand = "$dir$stem ($label)$ext"
        if (used.add(cand)) return cand
    }
    return name + "_" + System.nanoTime()
}

/** Writes files/folders into [z]. Returns (files added, files renamed because the name already existed). */
private fun addItems(z: ZipOutputStream, items: List<File>, used: MutableSet<String>, tag: String?, time: Long?, skip: List<File>): Pair<Int, Int> {
    var added = 0; var renamed = 0
    val skipPaths = skip.map { it.canonicalPath }.toSet()
    for (r in items) {
        val files = if (r.isDirectory) r.walkTopDown().filter { it.isFile }.toList() else listOf(r)
        for (f in files) {
            if (f.canonicalPath in skipPaths) continue
            val ins = try { f.inputStream() } catch (_: Exception) { continue }
            val rel = f.relativeTo(r.parentFile ?: r).path.replace(File.separatorChar, '/')
            val n = uniqueEntry(rel, used, tag)
            if (n != rel) renamed++
            val e = ZipEntry(n); e.time = time ?: f.lastModified()
            z.putNextEntry(e)
            ins.use { it.copyTo(z, 1 shl 16) }
            z.closeEntry(); added++
        }
    }
    return added to renamed
}

/** New zip from many files/folders (they may come from different folders). Returns files added. */
fun zipMany(items: List<File>, out: File): Int {
    var n = 0
    ZipOutputStream(out.outputStream().buffered(1 shl 16)).use { z ->
        z.setLevel(1)
        n = addItems(z, items, HashSet(), null, null, listOf(out)).first
    }
    return n
}

/**
 * Merge files into an EXISTING zip. Old entries are kept untouched. A new file whose name already exists is added as
 * "name (updated yyyy-MM-dd HH-mm-ss).ext". Every new entry is stamped with the time it was added.
 * Returns (files added, files that got the date/time name because they already existed).
 */
fun mergeZip(zip: File, items: List<File>): Pair<Int, Int> {
    val tmp = File(zip.parentFile, zip.name + ".merge.tmp")
    val now = System.currentTimeMillis(); val tag = stamp(now)
    val used = HashSet<String>()
    var res = 0 to 0
    try {
        ZipOutputStream(tmp.outputStream().buffered(1 shl 16)).use { z ->
            z.setLevel(1)
            ZipFile(zip).use { zf ->
                for (e in zf.entries().toList()) {
                    used.add(e.name)
                    val ne = ZipEntry(e.name); ne.time = e.time
                    z.putNextEntry(ne)
                    if (!e.isDirectory) zf.getInputStream(e).use { it.copyTo(z, 1 shl 16) }
                    z.closeEntry()
                }
            }
            res = addItems(z, items, used, tag, now, listOf(zip, tmp))
        }
        if (!zip.delete() || !tmp.renameTo(zip)) { tmp.copyTo(zip, true); tmp.delete() }
    } catch (e: Exception) { tmp.delete(); throw e }
    return res
}

fun readZip(f: File): List<ZEntry> = ZipFile(f).use { zf -> zf.entries().toList().map { ZEntry(it.name, it.size, it.time, it.isDirectory) } }

/** Extract [names] (null = everything) into [dir]. Never overwrites: clashes get "(extracted date time)" in the name. Returns files extracted. */
fun extractZip(zip: File, names: Set<String>?, dir: File): Int {
    dir.mkdirs()
    val base = dir.canonicalPath
    var n = 0
    ZipFile(zip).use { zf ->
        for (e in zf.entries().toList()) {
            if (names != null && names.none { e.name == it || (it.endsWith("/") && e.name.startsWith(it)) }) continue
            val t = File(dir, e.name)
            if (!t.canonicalPath.startsWith(base + File.separator)) continue   // zip-slip guard
            if (e.isDirectory) { t.mkdirs(); continue }
            t.parentFile?.mkdirs()
            val out = if (t.exists()) uniqStamp(t.parentFile!!, t.name, "extracted") else t
            zf.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            if (e.time > 0) out.setLastModified(e.time)
            n++
        }
    }
    return n
}

/** Writes the contents list of a zip (name | size | date time) to a .txt file. */
fun zipListTxt(zip: File, es: List<ZEntry>, out: File) {
    val files = es.filter { !it.dir }
    val total = files.sumOf { it.size.coerceAtLeast(0) }
    out.writeText("Zip: ${zip.name}\nPath: ${zip.path}\nFiles: ${files.size}\nTotal size: ${size(total)}\nListed on: ${dt(System.currentTimeMillis())}\n\n" +
        files.mapIndexed { i, e -> "${i + 1}. ${e.name}  |  ${size(e.size.coerceAtLeast(0))}  |  ${dt(e.time)}" }.joinToString("\n"))
}

/* ------------------------------------------------------------------- UI */

/** Pick an existing .zip anywhere on storage. */
@Composable fun ZipPicker(onPick: (File) -> Unit, onDismiss: () -> Unit) {
    var d by remember { mutableStateOf(ROOT) }
    val list = remember(d) {
        d.listFiles()?.filter { !it.name.startsWith(".") && (it.isDirectory || it.extension.equals("zip", true)) }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose a zip file") },
        text = { Column {
            Text(d.path, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.height(300.dp)) {
                item { Text("⬆ ..", Modifier.fillMaxWidth().clickable { d.parentFile?.let { d = it } }.padding(10.dp)) }
                items(list) { f ->
                    Text((if (f.isDirectory) "📁 " else "🗜️ ") + f.name, Modifier.fillMaxWidth().clickable { if (f.isDirectory) d = f else onPick(f) }.padding(10.dp))
                }
            }
        } },
        confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

/** Lists everything inside a zip: extract any entries, export the list to .txt, merge picked files in. */
@Composable fun ZipViewerDialog(zip: File, basket: Basket, onDismiss: () -> Unit, onChange: () -> Unit) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var r by remember { mutableIntStateOf(0) }
    val loaded by produceState<List<ZEntry>?>(null, zip, r) {
        value = withContext(Dispatchers.IO) { try { readZip(zip) } catch (e: Exception) { emptyList() } }
    }
    val es = loaded
    val sel = remember { mutableStateListOf<String>() }
    var ext by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    fun tog(n: String) { if (!sel.remove(n)) sel.add(n) }
    fun doExtract(dir: File) {
        val names = sel.toSet()
        scope.bg(c, onChange) { "Extracted ${extractZip(zip, names, dir)} file(s) to ${dir.name}" }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("🗜️ ${zip.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = { Column {
            Text(if (es == null) "Reading…" else "${es.count { !it.dir }} files • ${sel.size} selected", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton({ sel.clear(); es?.let { l -> sel.addAll(l.map { it.name }) } }) { Text("All") }
                TextButton({ sel.clear() }) { Text("None") }
                TextButton({
                    scope.bg(c, onChange) {
                        val out = uniq(zip.parentFile!!, zip.nameWithoutExtension + "_list.txt")
                        zipListTxt(zip, es ?: readZip(zip), out); "Saved ${out.name} (next to the zip)"
                    }
                }) { Text("📝 List→txt") }
                TextButton({
                    val items = basket.items.toList()
                    scope.bg(c, { r++; sel.clear(); onChange() }) {
                        val (a, u) = mergeZip(zip, items)
                        "Added $a file(s)" + if (u > 0) " • $u already existed → added with date/time in name" else ""
                    }
                }, enabled = basket.items.isNotEmpty()) { Text("➕ Add picked (${basket.items.size})") }
            }
            LazyColumn(Modifier.height(280.dp)) {
                items(es ?: emptyList<ZEntry>()) { e ->
                    Row(Modifier.fillMaxWidth().clickable { tog(e.name) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(e.name in sel, { tog(e.name) })
                        Column(Modifier.weight(1f)) {
                            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (e.dir) "Folder" else "${size(e.size.coerceAtLeast(0))} • added ${dt(e.time)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } },
        confirmButton = { TextButton({ ext = true }, enabled = sel.isNotEmpty()) { Text("Extract") } },
        dismissButton = { TextButton(onDismiss) { Text("Close") } })
    if (ext) AlertDialog(onDismissRequest = { ext = false }, title = { Text("Extract ${sel.size} item(s) to") },
        text = { Column {
            Button({ ext = false; doExtract(uniq(zip.parentFile!!, zip.nameWithoutExtension)) }, Modifier.fillMaxWidth()) { Text("Its own folder") }
            Text("New folder named after the zip, next to it", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ ext = false; pick = true }, Modifier.fillMaxWidth()) { Text("Choose / create folder…") }
        } },
        confirmButton = { TextButton({ ext = false }) { Text("Cancel") } })
    if (pick) FolderPicker(zip.parentFile ?: ROOT, { d -> pick = false; doExtract(d) }, { pick = false })
}
