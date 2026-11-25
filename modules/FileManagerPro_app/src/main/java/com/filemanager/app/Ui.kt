@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.filemanager.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shortcut
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------- models

data class TypeStyle(val icon: ImageVector, val bg: Color, val fg: Color)

fun styleOf(f: File, dir: Boolean = f.isDirectory): TypeStyle {
    if (dir) return TypeStyle(Icons.Default.Folder, PrimarySoft, PrimaryStrong)
    val e = FileOps.ext(f)
    return when {
        e == "pdf" -> TypeStyle(Icons.Default.PictureAsPdf, Color(0xFFFDECEA), Color(0xFFC62828))
        e == "csv" || e == "xlsx" || e == "xls" -> TypeStyle(Icons.Default.TableChart, Color(0xFFE8F5E9), Color(0xFF2E7D32))
        e == "doc" || e == "docx" -> TypeStyle(Icons.Default.Description, PrimarySoft, PrimaryStrong)
        e == "txt" || e == "log" -> TypeStyle(Icons.Default.TextSnippet, Surface2, TextSoft)
        e == "json" || e == "html" || e == "htm" || e == "xml" -> TypeStyle(Icons.Default.Code, Color(0xFFFFF4E0), Color(0xFFEF6C00))
        e == "md" -> TypeStyle(Icons.Default.Description, Color(0xFFF3E5F5), Color(0xFF7B1FA2))
        e == "zip" || e == "rar" || e == "7z" || e == "tar" || e == "gz" -> TypeStyle(Icons.Default.FolderZip, Color(0xFFFFF3E0), Color(0xFFE65100))
        e in FileOps.imgExts -> TypeStyle(Icons.Default.Image, Color(0xFFFCE4EC), Color(0xFFC2185B))
        e in FileOps.vidExts -> TypeStyle(Icons.Default.Videocam, Color(0xFFEDE7F6), Color(0xFF5E35B1))
        e in FileOps.audExts -> TypeStyle(Icons.Default.AudioFile, Color(0xFFE0F7FA), Color(0xFF00838F))
        else -> TypeStyle(Icons.Default.InsertDriveFile, Surface2, TextSoft)
    }
}

class Act(val label: String, val icon: ImageVector, val danger: Boolean = false, val run: () -> Unit)
class PickReq(val title: String, val onPick: (File) -> Unit)

sealed class Dlg {
    object NewFolder : Dlg()
    object NewFile : Dlg()
    class Rename(val f: File) : Dlg()
    class Delete(val f: File) : Dlg()
    class Details(val f: File) : Dlg()
    class Pin(val title: String, val onOk: () -> Unit) : Dlg()
    class SetPin(val onSet: () -> Unit) : Dlg()
    class Organize(val stat: ExtStat) : Dlg()
    object DeleteMany : Dlg()
    object ZipMany : Dlg()
}

// ---------------------------------------------------------------- root

@Composable
fun FileManagerApp(vm: FileViewModel) {
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    var sheetFor by remember { mutableStateOf<File?>(null) }
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var picker by remember { mutableStateOf<PickReq?>(null) }
    var showBasket by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var fabMenu by remember { mutableStateOf(false) }
    var dictation by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var showDrop by remember { mutableStateOf(false) }
    var dropChosen by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it.text) } }
    LaunchedEffect(vm.voice.notice) { vm.voice.notice?.let { vm.say(it) } }
    LaunchedEffect(dialog) { dictation = null }
    // Like the Voice File Manager: tick files and the app asks, out loud, what to do.
    LaunchedEffect(vm.selected) {
        if (vm.selected.isNotEmpty()) {
            kotlinx.coroutines.delay(1500)
            if (!vm.voice.on && vm.voice.autoAsk && hasMicPermission(ctx)) vm.voice.start()
            else vm.voice.askSelected(vm.selected.size)
        }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.voice.start() else vm.say("Microphone permission is needed for voice commands")
    }
    fun toggleVoice() {
        if (vm.voice.on) {
            vm.voice.stop(true)
        } else if (hasMicPermission(ctx)) {
            vm.voice.autoAsk = true
            vm.voice.start()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun guard(f: File, then: () -> Unit) {
        if (vm.blocked(f)) {
            dialog = Dlg.Pin("Enter PIN to open ${f.name}") { vm.unlockFor(f); then() }
        } else then()
    }

    fun openItem(f: File) = guard(f) {
        if (f.isDirectory) {
            vm.go(f)
        } else if (isViewable(f)) {
            vm.viewer = f
        } else if (!openExternal(ctx, f)) {
            vm.say("No app can open this file")
        }
    }

    fun actsFor(f: File): List<Act> {
        val l = mutableListOf<Act>()
        l += Act("Copy to folder", Icons.Default.ContentCopy) {
            picker = PickReq("Copy “${f.name}” to…") { vm.copyTo(f, it) }
        }
        l += Act("Move to folder", Icons.Default.DriveFileMove) {
            picker = PickReq("Move “${f.name}” to…") { vm.moveTo(f, it) }
        }
        l += Act("Add to Pick & Drop", Icons.Default.PlaylistAdd) { vm.pick(f) }
        l += Act("Create folder", Icons.Default.CreateNewFolder) { dialog = Dlg.NewFolder }
        l += Act("Create file", Icons.Default.NoteAdd) { dialog = Dlg.NewFile }
        l += Act("Rename", Icons.Default.Edit) { dialog = Dlg.Rename(f) }
        l += Act("Share", Icons.Default.Share) {
            if (f.isDirectory) vm.say("Compress the folder first, then share the zip") else shareFile(ctx, f)
        }
        if (f.isFile && FileOps.ext(f) == "zip") {
            l += Act("Uncompress", Icons.Default.FolderZip) { vm.uncompress(f) }
        } else {
            l += Act("Compress to zip", Icons.Default.FolderZip) { vm.compress(f) }
        }
        l += Act("Copy file path", Icons.Default.Link) {
            copyToClipboard(ctx, f.absolutePath)
            vm.say("Path copied")
        }
        val bm = f.absolutePath in vm.bookmarks
        l += Act(if (bm) "Remove bookmark" else "Bookmark", if (bm) Icons.Default.Bookmark else Icons.Default.BookmarkBorder) {
            vm.toggleBookmark(f)
        }
        l += Act("Make shortcut", Icons.Default.Shortcut) {
            if (makeShortcut(ctx, f)) vm.say("Confirm the shortcut on your home screen")
            else vm.say("Your launcher doesn't support pinned shortcuts")
        }
        l += Act("Open file location", Icons.Default.FolderOpen) { vm.openLocation(f) }
        l += Act("Details", Icons.Default.Info) { dialog = Dlg.Details(f) }
        l += Act("Export directory to txt", Icons.Default.TextSnippet) {
            vm.exportDir(if (f.isDirectory) f else (f.parentFile ?: vm.root))
        }
        if (f.isDirectory) {
            val hid = f.absolutePath in vm.hidden
            l += Act(if (hid) "Unhide folder" else "Hide folder", if (hid) Icons.Default.Visibility else Icons.Default.VisibilityOff) {
                vm.toggleHide(f)
            }
            if (f.absolutePath in vm.locked) {
                l += Act("Remove lock", Icons.Default.LockOpen) {
                    dialog = Dlg.Pin("Enter PIN to remove the lock") { vm.removeLock(f) }
                }
            } else {
                l += Act("Lock folder", Icons.Default.Lock) {
                    if (vm.store.hasPin()) {
                        vm.lock(f)
                    } else {
                        dialog = Dlg.SetPin { vm.lock(f) }
                    }
                }
            }
        }
        l += Act("Delete", Icons.Default.Delete, true) { dialog = Dlg.Delete(f) }
        return l
    }

    // ---------------------------------------------------------------- drop panel (voice or touch)
    fun openDropPanel() {
        when {
            vm.basket.isEmpty() -> vm.say("Nothing picked up yet. Select items and say “copy” or “move”")
            vm.tab != "folders" -> vm.say("Open a folder first")
            vm.blocked(vm.path) -> vm.say("This folder is locked")
            else -> {
                dropChosen = if (vm.basket.size == 1) setOf(vm.basket[0].absolutePath) else emptySet()
                showDrop = true
                vm.say("Say the numbers to drop (any order) or “all”. Then say “drop”")
            }
        }
    }

    fun runDrop() {
        val list = vm.basket.filter { it.absolutePath in dropChosen }
        if (list.isEmpty()) { vm.say("Choose items first — say numbers or “all”"); return }
        showDrop = false
        vm.dropChosen(list)
    }

    fun pasteNow() {
        when {
            vm.basket.isEmpty() -> vm.say("Nothing to paste. Select items and say copy or move first")
            vm.tab != "folders" -> vm.say("Open a folder first")
            vm.blocked(vm.path) -> vm.say("This folder is locked")
            else -> vm.dropChosen(vm.basket.toList())
        }
    }

    fun dropVoice(cmd: VCmd) {
        val all = vm.basket.toList()
        fun choose(nums: List<Int>) {
            nums.forEach { n -> all.getOrNull(n - 1)?.let { dropChosen = dropChosen + it.absolutePath } }
        }
        when (cmd) {
            is VCmd.Num -> {
                val f = all.getOrNull(cmd.n - 1)
                if (f == null) vm.say("No picked item ${cmd.n}")
                else dropChosen = if (f.absolutePath in dropChosen) dropChosen - f.absolutePath else dropChosen + f.absolutePath
            }
            is VCmd.Select -> choose(cmd.nums)
            is VCmd.Unselect -> cmd.nums.forEach { n -> all.getOrNull(n - 1)?.let { dropChosen = dropChosen - it.absolutePath } }
            is VCmd.SelectAll -> dropChosen = all.map { it.absolutePath }.toSet()
            is VCmd.ClearSelection -> dropChosen = emptySet()
            is VCmd.Do -> {
                if (cmd.verb == Verb.COPY || cmd.verb == Verb.MOVE || cmd.verb == Verb.DROP || cmd.verb == Verb.PICKUP) {
                    if (cmd.verb == Verb.COPY) vm.basketMove = false
                    if (cmd.verb == Verb.MOVE) vm.basketMove = true
                    if (cmd.arg == "all") dropChosen = all.map { it.absolutePath }.toSet()
                    choose(cmd.nums)
                    if (cmd.verb == Verb.DROP) runDrop()
                    else vm.say(if (vm.basketMove) "Mode: move" else "Mode: copy")
                } else {
                    vm.say("Say numbers, “move” or “copy”, then “drop”")
                }
            }
            is VCmd.Yes -> runDrop()
            is VCmd.No -> showDrop = false
            is VCmd.NewItem -> {
                if (cmd.folder && cmd.name.isNotBlank()) {
                    val nf = File(vm.path, cmd.name)
                    if (nf.isDirectory || nf.mkdirs()) {
                        vm.go(nf)
                        vm.say("Dropping into “${nf.name}”. Say “drop”")
                    } else {
                        vm.say("Could not create that folder")
                    }
                } else {
                    vm.say("Say: new folder, then the name")
                }
            }
            is VCmd.Stop -> vm.voice.stop(true)
            is VCmd.Help -> showHelp = true
            else -> vm.say("Say numbers (any order) or “all”, “move” or “copy”, then “drop”")
        }
    }

    // ---------------------------------------------------------------- voice commands
    fun doVerb(c: VCmd.Do) {
        if (c.verb == Verb.PASTE) { pasteNow(); return }
        if (c.verb == Verb.DROP) {
            openDropPanel()
            if (showDrop && (c.arg == "all" || c.nums.isNotEmpty())) {
                val all = vm.basket.toList()
                dropChosen = if (c.arg == "all") all.map { it.absolutePath }.toSet()
                    else c.nums.mapNotNull { all.getOrNull(it - 1)?.absolutePath }.toSet()
                runDrop()
            }
            return
        }
        if (vm.tab == "apps" || vm.tab == "organizer") { vm.say("Open a folder or file list first"); return }
        if (c.arg == "all") {
            vm.selectEverything()
        } else if (c.nums.isNotEmpty()) {
            vm.selected = emptySet()
            if (vm.selectNums(c.nums) == 0) { vm.say("No item with that number"); return }
        }
        val nameArg = c.arg
        if (c.verb == Verb.OPEN && nameArg != null && nameArg != "all" && c.nums.isEmpty()) {
            val hit = vm.items.firstOrNull { it.file.name.contains(nameArg, ignoreCase = true) }
            if (hit == null) vm.say("No item named “$nameArg” here") else openItem(hit.file)
            return
        }
        var files = vm.selectedList()
        if (files.isEmpty()) {
            if (c.verb == Verb.COPYPATH) {
                copyToClipboard(ctx, vm.path.absolutePath)
                vm.say("Folder path copied")
                return
            }
            if (c.verb == Verb.BACKUP && vm.tab == "folders" && vm.path != vm.root) files = listOf(vm.path)
            if (files.isEmpty()) {
                vm.say("Select items first — say “select 2 4”, or add numbers like “copy 3”")
                return
            }
        }
        when (c.verb) {
            Verb.OPEN -> {
                if (files.size == 1) { vm.stopSelecting(); openItem(files[0]) }
                else vm.say("Say one number to open, like “open 5”")
            }
            Verb.COPY -> vm.voicePick(false)
            Verb.MOVE -> vm.voicePick(true)
            Verb.PICKUP -> vm.voicePick(null)
            Verb.DROP -> openDropPanel()
            Verb.PASTE -> pasteNow()
            Verb.RENAME -> {
                if (files.size != 1) {
                    vm.say("Select just one item to rename")
                } else if (nameArg != null && nameArg != "all") {
                    vm.stopSelecting()
                    vm.rename(files[0], keepExt(files[0], nameArg))
                } else {
                    dictation = null
                    dialog = Dlg.Rename(files[0])
                    vm.say("Say the new name, then “confirm”")
                }
            }
            Verb.DELETE -> {
                dialog = if (files.size == 1) Dlg.Delete(files[0]) else Dlg.DeleteMany
                vm.say("Say “yes” to delete ${files.size} item${if (files.size == 1) "" else "s"}, or “cancel”")
            }
            Verb.BOOKMARK -> { files.forEach { vm.toggleBookmark(it) }; vm.stopSelecting() }
            Verb.ZIP -> vm.zipAuto()
            Verb.UNZIP -> { if (vm.selectedHasZip()) vm.uncompressSelected() else vm.say("No zip file selected") }
            Verb.BACKUP -> vm.backup(files)
            Verb.SHARE -> {
                val fs = files.filter { it.isFile }
                if (fs.isEmpty()) {
                    vm.say("Folders can’t be shared — say “zip” first")
                } else {
                    shareFiles(ctx, fs)
                    vm.stopSelecting()
                }
            }
            Verb.COPYPATH -> {
                copyToClipboard(ctx, files.joinToString("\n") { it.absolutePath })
                vm.say("Path copied")
                vm.stopSelecting()
            }
            Verb.DETAILS -> {
                if (files.size == 1) dialog = Dlg.Details(files[0]) else vm.say("Select one item for details")
            }
        }
    }

    fun runVoice(ev: VEvent) {
        if (ev.handled) return
        ev.handled = true
        val cmd = ev.cmd
        if (showDrop) { dropVoice(cmd); return }
        val d = dialog
        val naming = d is Dlg.NewFolder || d is Dlg.NewFile || d is Dlg.Rename || d is Dlg.ZipMany
        if (naming && cmd !is VCmd.Yes && cmd !is VCmd.No && cmd !is VCmd.Stop) {
            val nm = spokenName(ev.heard)
            if (nm.isNotBlank()) {
                val v = if (d is Dlg.Rename) keepExt(d.f, nm) else nm
                dictation = v
                vm.say("Name: $v — say “confirm” or “cancel”")
            }
            return
        }
        val overlay = (d != null && !naming) || picker != null || showBasket || sheetFor != null
        if (overlay && cmd !is VCmd.Yes && cmd !is VCmd.No && cmd !is VCmd.Stop && cmd !is VCmd.Help) {
            vm.say("Say “cancel” to close this first")
            return
        }
        val listTab = vm.tab != "apps" && vm.tab != "organizer"
        when (cmd) {
            is VCmd.Stop -> vm.voice.stop(true)
            is VCmd.Help -> showHelp = true
            is VCmd.Yes -> {
                val t = dictation
                when (d) {
                    is Dlg.Delete -> { dialog = null; vm.stopSelecting(); vm.delete(d.f) }
                    is Dlg.DeleteMany -> { dialog = null; vm.deleteSelected() }
                    is Dlg.Rename -> {
                        if (t.isNullOrBlank()) vm.say("Say the new name first")
                        else { dialog = null; vm.stopSelecting(); vm.rename(d.f, t) }
                    }
                    is Dlg.NewFolder -> {
                        if (t.isNullOrBlank()) vm.say("Say the folder name first")
                        else { dialog = null; vm.create(t, true) }
                    }
                    is Dlg.NewFile -> {
                        if (t.isNullOrBlank()) vm.say("Say the file name first")
                        else { dialog = null; vm.create(t, false) }
                    }
                    is Dlg.ZipMany -> {
                        if (t.isNullOrBlank()) vm.say("Say the zip name first")
                        else { dialog = null; vm.zipSelected(t) }
                    }
                    is Dlg.Details -> { dialog = null }
                    else -> vm.say("Nothing to confirm")
                }
            }
            is VCmd.No -> {
                when {
                    dialog != null -> dialog = null
                    picker != null -> picker = null
                    showBasket -> showBasket = false
                    sheetFor != null -> sheetFor = null
                    showHelp -> showHelp = false
                    vm.selecting -> vm.stopSelecting()
                    else -> vm.say("Nothing to cancel")
                }
            }
            is VCmd.Back -> {
                if (vm.selecting) vm.stopSelecting()
                else if (vm.canGoUp()) vm.up()
                else if (vm.tab != "folders") vm.setTab("folders")
                else vm.say("Already at the top")
            }
            is VCmd.Home -> vm.goHome()
            is VCmd.Refresh -> vm.refresh()
            is VCmd.Search -> { showSearch = true; vm.updateQuery(cmd.q); vm.say("Searching: ${cmd.q}") }
            is VCmd.ClearSearch -> { vm.updateQuery(""); showSearch = false }
            is VCmd.SelectAll -> { if (listTab) vm.selectEverything() else vm.say("Open a file list first") }
            is VCmd.ClearSelection -> vm.stopSelecting()
            is VCmd.Select -> {
                if (!listTab) vm.say("Open a file list first")
                else if (cmd.nums.isEmpty()) { vm.selecting = true; vm.say("Selection on — say the serial numbers") }
                else {
                    val n = vm.selectNums(cmd.nums)
                    vm.say(if (n == 0) "No item with that number" else "$n selected")
                }
            }
            is VCmd.Unselect -> vm.unselectNums(cmd.nums)
            is VCmd.SelectMatch -> {
                val hits = vm.items.filter { matchPredicate(cmd.q)(it.file) }
                if (!listTab) {
                    vm.say("Open a file list first")
                } else if (hits.isEmpty()) {
                    vm.say("No items match ${cmd.q}")
                } else {
                    vm.selecting = true
                    vm.selected = vm.selected + hits.map { it.path }
                    vm.say("Selected ${hits.size}")
                }
            }
            is VCmd.Num -> {
                val e = vm.itemAt(cmd.n)
                when {
                    !listTab -> vm.say("Open a file list first")
                    e == null -> vm.say("There is no item number ${cmd.n}")
                    e.isDir -> {
                        openItem(e.file)
                        if (vm.voice.quickPaste && vm.basket.isNotEmpty() && vm.path == e.file) pasteNow()
                    }
                    else -> vm.toggleNum(cmd.n)
                }
            }
            is VCmd.NewItem -> {
                if (vm.tab != "folders") {
                    vm.say("Open a folder first")
                } else if (cmd.name.isNotBlank()) {
                    vm.create(cmd.name, cmd.folder)
                } else {
                    dictation = null
                    dialog = if (cmd.folder) Dlg.NewFolder else Dlg.NewFile
                    vm.say("Say the name, then “confirm”")
                }
            }
            is VCmd.Do -> doVerb(cmd)
            is VCmd.Text -> vm.say("Didn’t understand “${cmd.s}”. Say “help” for commands")
        }
    }

    LaunchedEffect(vm.voice.event) { vm.voice.event?.let { runVoice(it) } }

    BackHandler(enabled = true) {
        when {
            vm.viewer != null -> { vm.viewer = null }
            vm.selecting -> { vm.stopSelecting() }
            vm.tab != "folders" -> { vm.setTab("folders") }
            vm.canGoUp() -> { vm.up() }
            else -> { (ctx as? Activity)?.finish() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Bg,
            snackbarHost = { SnackbarHost(snack) },
            floatingActionButton = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VoiceHelpButton(onClick = { showHelp = true })
                VoiceMicButton(vm.voice.on, onClick = { toggleVoice() })
                if (vm.tab == "folders") {
                    Box {
                        FloatingActionButton(
                            onClick = { fabMenu = true },
                            containerColor = Primary,
                            contentColor = Color.White
                        ) { Icon(Icons.Default.Add, "New") }
                        DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("New folder") },
                                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                onClick = { fabMenu = false; dialog = Dlg.NewFolder }
                            )
                            DropdownMenuItem(
                                text = { Text("New file") },
                                leadingIcon = { Icon(Icons.Default.NoteAdd, null) },
                                onClick = { fabMenu = false; dialog = Dlg.NewFile }
                            )
                        }
                    }
                }
                }
            },
            bottomBar = {
                Column {
                    if (vm.selecting && vm.selected.isNotEmpty()) {
                        SelectionBar(
                            vm,
                            onCopy = {
                                picker = PickReq("Copy ${vm.selected.size} items to…") { vm.copySelected(it) }
                            },
                            onMove = {
                                picker = PickReq("Move ${vm.selected.size} items to…") { vm.moveSelected(it) }
                            },
                            onDelete = { dialog = Dlg.DeleteMany },
                            onZip = { dialog = Dlg.ZipMany }
                        )
                    }
                    if (vm.basket.isNotEmpty()) BasketBar(vm, onView = { showBasket = true })
                }
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                Header(
                    vm,
                    onCrumb = { f -> guard(f) { vm.go(f) } },
                    showSearch = showSearch,
                    onToggleSearch = {
                        showSearch = !showSearch
                        if (!showSearch) vm.updateQuery("")
                    },
                    onShowHidden = {
                        if (vm.showHidden) {
                            vm.toggleShowHidden()
                        } else if (vm.store.hasPin()) {
                            dialog = Dlg.Pin("Enter PIN to show hidden folders") { vm.toggleShowHidden() }
                        } else {
                            vm.toggleShowHidden()
                        }
                    },
                    onPin = {
                        if (vm.store.hasPin()) {
                            dialog = Dlg.Pin("Enter your current PIN") {
                                dialog = Dlg.SetPin { vm.say("PIN updated") }
                            }
                        } else {
                            dialog = Dlg.SetPin { vm.say("PIN set") }
                        }
                    },
                    onExport = { vm.exportDir(vm.path) },
                    onBasket = { showBasket = true },
                    onClose = { (ctx as? Activity)?.finishAndRemoveTask() }
                )
                if (showSearch) {
                    OutlinedTextField(
                        value = vm.query,
                        onValueChange = { vm.updateQuery(it) },
                        singleLine = true,
                        placeholder = { Text("Search files") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (vm.query.isNotEmpty()) {
                                IconButton(onClick = { vm.updateQuery("") }) { Icon(Icons.Default.Close, "Clear") }
                            }
                        },
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                if (vm.voice.on) {
                    VoiceStatusCard(
                        vm.voice.partial, vm.voice.heard,
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                StorageBar(vm)
                TabsRow(vm)
                if (vm.tab != "apps" && vm.tab != "organizer") {
                    ExtRow(vm)
                    SortRow(vm, onQuick = { f -> guard(f) { vm.go(f) } })
                }
                if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Primary)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (vm.tab) {
                        "apps" -> AppDrawer(vm.query)
                        "organizer" -> OrganizerScreen(
                            vm,
                            onChange = {
                                picker = PickReq("Choose a folder to organize") { vm.setPath(it) }
                            },
                            onOrganize = { dialog = Dlg.Organize(it) }
                        )
                        else -> FileList(
                            vm,
                            onOpen = { openItem(it) },
                            onMenu = { sheetFor = it }
                        )
                    }
                }
            }
        }
        vm.viewer?.let { ViewerScreen(vm, it) }
    }

    // ---- sheets & dialogs
    if (showHelp) VoiceHelpDialog(onDismiss = { showHelp = false }, listening = vm.voice.on, onStart = { toggleVoice() })
    if (showDrop) {
        DropPanel(
            dest = vm.path.name.ifEmpty { "Internal storage" },
            items = vm.basket.toList(),
            chosen = dropChosen,
            move = vm.basketMove,
            onToggle = { f ->
                dropChosen = if (f.absolutePath in dropChosen) dropChosen - f.absolutePath else dropChosen + f.absolutePath
            },
            onMode = { vm.basketMove = it },
            onDrop = { runDrop() },
            onCancel = { showDrop = false }
        )
    }
    sheetFor?.let { f ->
        ActionSheet(f, actsFor(f)) { sheetFor = null }
    }
    if (showBasket) {
        BasketSheet(
            vm,
            onPickDest = {
                showBasket = false
                picker = PickReq("Drop ${vm.basket.size} items into…") { vm.dropAllTo(it) }
            }
        ) { showBasket = false }
    }

    picker?.let { req ->
        FolderPicker(
            vm, req.title,
            onPick = { picker = null; req.onPick(it) },
            onDismiss = { picker = null }
        )
    }

    when (val d = dialog) {
        null -> {}
        is Dlg.NewFolder -> NameDialog("New folder", "", "Folder name", "Create",
            onDone = { dialog = null; vm.create(it, true) }, onDismiss = { dialog = null }, spoken = dictation)
        is Dlg.NewFile -> NameDialog("New file", "", "File name (e.g. notes.txt)", "Create",
            onDone = { dialog = null; vm.create(it, false) }, onDismiss = { dialog = null }, spoken = dictation)
        is Dlg.Rename -> NameDialog("Rename", d.f.name, "New name", "Rename",
            onDone = { dialog = null; vm.rename(d.f, it) }, onDismiss = { dialog = null }, spoken = dictation)
        is Dlg.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete?") },
            text = { Text("“${d.f.name}” will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { dialog = null; vm.delete(d.f) }) { Text("Delete", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
        )
        is Dlg.ZipMany -> NameDialog(
            "Zip ${vm.selected.size} items into one file", "Archive", "Zip file name", "Create zip",
            onDone = { dialog = null; vm.zipSelected(it) }, onDismiss = { dialog = null }, spoken = dictation
        )
        is Dlg.DeleteMany -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${vm.selected.size} items?") },
            text = { Text("The selected files and folders will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { dialog = null; vm.deleteSelected() }) { Text("Delete", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
        )
        is Dlg.Details -> DetailsDialog(vm, d.f) { dialog = null }
        is Dlg.Pin -> PinEntryDialog(
            d.title, verify = { vm.verifyPin(it) },
            onOk = { dialog = null; d.onOk() }, onDismiss = { dialog = null }
        )
        is Dlg.SetPin -> SetPinDialog(
            onSet = { vm.savePin(it); dialog = null; d.onSet() }, onDismiss = { dialog = null }
        )
        is Dlg.Organize -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Organize .${d.stat.ext} files") },
            text = {
                Text(
                    "${d.stat.count} files (${FileOps.size(d.stat.size)}) found in ${vm.path.name.ifEmpty { "Internal" }} " +
                        "will go to a folder named ${d.stat.ext.uppercase()}_Files."
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { dialog = null; vm.organize(d.stat.ext, false) }) { Text("Copy") }
                    TextButton(onClick = { dialog = null; vm.organize(d.stat.ext, true) }) { Text("Move") }
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------- header / bars

@Composable
fun Pill(label: String, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) Primary else Color.White,
        contentColor = if (selected) Color.White else TextSoft,
        border = if (selected) null else BorderStroke(1.dp, Border)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun Header(
    vm: FileViewModel,
    onCrumb: (File) -> Unit,
    showSearch: Boolean,
    onToggleSearch: () -> Unit,
    onShowHidden: () -> Unit,
    onPin: () -> Unit,
    onExport: () -> Unit,
    onBasket: () -> Unit,
    onClose: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val segs = remember(vm.path) {
        val list = mutableListOf<Pair<String, File>>("Internal" to vm.root)
        var cur = vm.root
        vm.path.absolutePath.removePrefix(vm.root.absolutePath).split("/").filter { it.isNotEmpty() }.forEach {
            cur = File(cur, it)
            list.add(it to cur)
        }
        list
    }
    LaunchedEffect(vm.path) { scroll.scrollTo(Int.MAX_VALUE) }

    Row(
        Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Primary),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Folder, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(8.dp))
        if (vm.tab == "folders") {
            IconButton(onClick = { vm.up() }, enabled = vm.canGoUp(), modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ArrowUpward, "Up")
            }
            Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                segs.forEachIndexed { i, (name, file) ->
                    if (i > 0) Icon(Icons.Default.ChevronRight, null, Modifier.size(14.dp), tint = TextFaint)
                    val last = i == segs.lastIndex
                    Text(
                        name,
                        Modifier.clip(CircleShape)
                            .background(if (last) PrimarySoft else Color.Transparent)
                            .clickable { onCrumb(file) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = if (last) Primary else TextSoft, maxLines = 1
                    )
                }
            }
        } else {
            Text(
                tabLabel(vm.tab), Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp
            )
        }
        IconButton(onClick = { vm.toggleSelecting() }) {
            Icon(
                if (vm.selecting) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                "Select multiple", tint = if (vm.selecting) Primary else TextSoft
            )
        }
        IconButton(onClick = onToggleSearch) {
            Icon(if (showSearch) Icons.Default.Close else Icons.Default.Search, "Search")
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (vm.showHidden) "Hide hidden folders" else "Show hidden folders") },
                    leadingIcon = { Icon(if (vm.showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) },
                    onClick = { menu = false; onShowHidden() }
                )
                DropdownMenuItem(
                    text = { Text(if (vm.store.hasPin()) "Change PIN" else "Set PIN (for locked folders)") },
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    onClick = { menu = false; onPin() }
                )
                DropdownMenuItem(
                    text = { Text("Export directory to txt") },
                    leadingIcon = { Icon(Icons.Default.TextSnippet, null) },
                    onClick = { menu = false; onExport() }
                )
                DropdownMenuItem(
                    text = { Text("Pick & Drop basket (${vm.basket.size})") },
                    leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) },
                    onClick = { menu = false; onBasket() }
                )
                DropdownMenuItem(
                    text = { Text("Close app") },
                    leadingIcon = { Icon(Icons.Default.Close, null) },
                    onClick = { menu = false; onClose() }
                )
            }
        }
    }
    HorizontalDivider(color = Border)
}

fun tabLabel(id: String) = when (id) {
    "folders" -> "Folders"
    "images" -> "Images"
    "videos" -> "Videos"
    "documents" -> "Documents"
    "apps" -> "App Drawer"
    "bookmarks" -> "Bookmarks"
    else -> "Organizer"
}

@Composable
fun StorageBar(vm: FileViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Storage", fontSize = 11.sp, color = TextSoft, fontWeight = FontWeight.Medium)
            Text(
                "${FileOps.size(vm.usedBytes)} / ${FileOps.size(vm.totalBytes)}",
                fontSize = 11.sp, color = TextSoft, fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = (vm.usedBytes.toFloat() / vm.totalBytes.toFloat()).coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = Primary, trackColor = Surface2
        )
    }
}

@Composable
fun TabsRow(vm: FileViewModel) {
    val tabs = listOf(
        Triple("folders", "Folders", Icons.Default.Folder),
        Triple("images", "Images", Icons.Default.Image),
        Triple("videos", "Videos", Icons.Default.Videocam),
        Triple("documents", "Documents", Icons.Default.Description),
        Triple("apps", "App Drawer", Icons.Default.Apps),
        Triple("bookmarks", "Bookmarks", Icons.Default.Bookmark),
        Triple("organizer", "Organizer", Icons.Default.Inventory2)
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tabs) { (id, label, icon) ->
            Pill(label, vm.tab == id, icon) { vm.setTab(id) }
        }
    }
}

@Composable
fun ExtRow(vm: FileViewModel) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(FileOps.chips) { c -> Pill(c, vm.extFilter == c) { vm.setExt(c) } }
    }
}

@Composable
fun SortRow(vm: FileViewModel, onQuick: (File) -> Unit) {
    val quick = remember(vm.root) {
        listOf("DCIM", "Download", "Documents", "Pictures", "Music", "Movies")
            .map { File(vm.root, it) }.filter { it.isDirectory }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item { Pill("Size", vm.sortKey == "size", Icons.Default.SwapVert) { vm.setSort("size") } }
        item { Pill("Last Modified", vm.sortKey == "modified", Icons.Default.Schedule) { vm.setSort("modified") } }
        item { Pill("A-Z", vm.sortKey == "name", Icons.Default.SortByAlpha) { vm.setSort("name") } }
        if (vm.tab == "folders") {
            item { Spacer(Modifier.width(8.dp)) }
            item { Pill("Internal", false, Icons.Default.Folder) { onQuick(vm.root) } }
            items(quick) { d -> Pill(d.name, false, Icons.Default.FolderOpen) { onQuick(d) } }
        }
    }
}

@Composable
fun BasketBar(vm: FileViewModel, onView: () -> Unit) {
    Surface(color = Color.White, shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.PlaylistAdd, null, tint = Primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("${vm.basket.size} picked", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    "${if (vm.basketMove) "Move" else "Copy"} → ${vm.path.name.ifEmpty { "Internal" }}",
                    fontSize = 11.sp, color = TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onView) { Text("View") }
            Button(
                onClick = { vm.dropAll() },
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) { Text("Drop here") }
            IconButton(onClick = { vm.clearBasket() }) { Icon(Icons.Default.Close, "Clear") }
        }
    }
}

// ---------------------------------------------------------------- list

@Composable
fun BarAction(icon: ImageVector, label: String, tint: Color = TextSoft, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = tint)
        Text(label, fontSize = 10.sp, color = tint)
    }
}

@Composable
fun SelectionBar(
    vm: FileViewModel, onCopy: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit, onZip: () -> Unit
) {
    Surface(color = Color.White, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.stopSelecting() }) { Icon(Icons.Default.Close, "Stop selecting") }
                Text(
                    "${vm.selected.size} selected", Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                )
                TextButton(onClick = { vm.selectAll() }) {
                    Text(if (vm.selected.size >= vm.items.size) "Select none" else "Select all")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BarAction(Icons.Default.ContentCopy, "Copy", onClick = onCopy)
                BarAction(Icons.Default.DriveFileMove, "Move", onClick = onMove)
                BarAction(Icons.Default.FolderZip, "Zip", onClick = onZip)
                if (vm.selectedHasZip()) BarAction(Icons.Default.FolderOpen, "Unzip") { vm.uncompressSelected() }
                BarAction(Icons.Default.PlaylistAdd, "Pick") { vm.pickSelected() }
                BarAction(Icons.Default.Delete, "Delete", Danger, onDelete)
            }
        }
    }
}

object Thumbs {
    private val cache = LruCache<String, ImageBitmap>(300)
    fun get(k: String): ImageBitmap? = cache.get(k)
    fun put(k: String, v: ImageBitmap) { cache.put(k, v) }
}

@Composable
fun FileList(vm: FileViewModel, onOpen: (File) -> Unit, onMenu: (File) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(vm.items, vm.highlight) {
        val i = vm.items.indexOfFirst { it.path == vm.highlight }
        if (i >= 0) state.scrollToItem(i)
    }
    if (vm.items.isEmpty() && !vm.loading) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Surface2),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Search, null, tint = TextFaint) }
            Spacer(Modifier.height(12.dp))
            Text("No files found", fontWeight = FontWeight.SemiBold)
            Text("Try changing filters or search", fontSize = 13.sp, color = TextSoft)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "${vm.items.size} items • " + (if (vm.tab == "folders") vm.path.name.ifEmpty { "Internal" } else tabLabel(vm.tab)),
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp, color = TextSoft
        )
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxWidth().background(Color.White),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            itemsIndexed(vm.items, key = { _, e -> e.path }) { i, e ->
                FileRow(
                    vm, e, i + 1, e.path == vm.highlight, e.path in vm.selected, vm.selecting,
                    { onOpen(e.file) }, { onMenu(e.file) }
                )
            }
        }
    }
}

@Composable
fun FileRow(
    vm: FileViewModel, e: Entry, index: Int, highlighted: Boolean, checked: Boolean, selecting: Boolean,
    onClick: () -> Unit, onLong: () -> Unit
) {
    val f = e.file
    val toggle: () -> Unit = { vm.toggleSelect(e.path) }
    val click: () -> Unit = if (selecting) toggle else onClick
    val longClick: () -> Unit = if (selecting) toggle else onLong
    val st = remember(e.path) { styleOf(f, e.isDir) }
    val meta = remember(e.path, e.created, e.size) {
        (if (e.isDir) "Folder" else FileOps.size(e.size)) + " • " + FileOps.date(e.created)
    }
    val isImage = !e.isDir && FileOps.ext(f) in FileOps.imgExts
    Column {
        Row(
            Modifier.fillMaxWidth()
                .background(if (checked || highlighted) PrimarySoft else Color.White)
                .combinedClickable(onClick = click, onLongClick = longClick)
                .padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) Checkbox(checked = checked, onCheckedChange = { toggle() })
            else Spacer(Modifier.width(6.dp))
            Text(
                "$index", Modifier.width(24.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = Primary, textAlign = TextAlign.Center, maxLines = 1
            )
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(st.bg),
                contentAlignment = Alignment.Center
            ) {
                if (isImage) {
                    val thumb by produceState<ImageBitmap?>(Thumbs.get(e.path), e.path) {
                        if (value == null) {
                            value = withContext(Dispatchers.IO) {
                                runCatching { FileOps.decode(f, 96) }.getOrNull()?.asImageBitmap()?.also { Thumbs.put(e.path, it) }
                            }
                        }
                    }
                    val t = thumb
                    if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(st.icon, null, tint = st.fg)
                } else {
                    Icon(st.icon, null, tint = st.fg)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    f.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(meta, fontSize = 11.sp, color = TextSoft, maxLines = 1)
            }
            if (e.path in vm.bookmarks) Icon(Icons.Default.Bookmark, null, Modifier.size(14.dp), tint = Primary)
            if (vm.showHidden && vm.isHidden(f)) Icon(Icons.Default.VisibilityOff, null, Modifier.size(14.dp), tint = TextFaint)
            if (e.path in vm.locked) Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = TextFaint)
            IconButton(onClick = onLong, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.MoreVert, "More", Modifier.size(20.dp), tint = TextFaint)
            }
        }
        HorizontalDivider(color = Border, modifier = Modifier.padding(start = if (selecting) 128.dp else 86.dp))
    }
}

// ---------------------------------------------------------------- sheets

@Composable
fun ActionSheet(f: File, acts: List<Act>, onDismiss: () -> Unit) {
    val st = styleOf(f)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(st.bg),
                    contentAlignment = Alignment.Center
                ) { Icon(st.icon, null, tint = st.fg) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (f.isDirectory) "Folder" else "File", fontSize = 12.sp, color = TextSoft)
                }
            }
            HorizontalDivider(color = Border)
            acts.forEach { a ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onDismiss(); a.run() }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(a.icon, null, tint = if (a.danger) Danger else TextSoft)
                    Spacer(Modifier.width(16.dp))
                    Text(a.label, color = if (a.danger) Danger else TextMain, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
fun BasketSheet(vm: FileViewModel, onPickDest: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Pick & Drop", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Pick files from any folders, then drop them into the current folder (${vm.path.name.ifEmpty { "Internal" }}) " +
                    "— all at once, or one by one into different folders.",
                fontSize = 12.sp, color = TextSoft, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Copy", !vm.basketMove) { vm.basketMove = false }
                Pill("Move", vm.basketMove) { vm.basketMove = true }
            }
            Spacer(Modifier.height(10.dp))
            if (vm.basket.isEmpty()) {
                Text("Nothing picked yet. Long-press or use ⋮ on a file → Add to Pick & Drop.", color = TextSoft, fontSize = 13.sp)
            } else {
                Button(
                    onClick = { vm.dropAll(); onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Drop all here") }
                OutlinedButton(onClick = onPickDest, modifier = Modifier.fillMaxWidth()) {
                    Text("Drop all into another / new folder…")
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.height(300.dp)) {
                    items(vm.basket.toList(), key = { it.absolutePath }) { f ->
                        val st = styleOf(f)
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(st.bg),
                                contentAlignment = Alignment.Center
                            ) { Icon(st.icon, null, tint = st.fg, modifier = Modifier.size(18.dp)) }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(f.parent ?: "", fontSize = 10.sp, color = TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { vm.dropOne(f) }) { Text("Drop here") }
                            IconButton(onClick = { vm.unpick(f) }) { Icon(Icons.Default.Close, "Remove", tint = TextFaint) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FolderPicker(vm: FileViewModel, title: String, onPick: (File) -> Unit, onDismiss: () -> Unit) {
    var cur by remember { mutableStateOf(vm.path) }
    var creating by remember { mutableStateOf(false) }
    val dirs = remember(cur, vm.hidden, vm.unlocked, vm.showHidden) {
        (cur.listFiles()
            ?.filter { it.isDirectory && !vm.blocked(it) && (vm.showHidden || !vm.isHidden(it)) }
            ?.sortedBy { it.name.lowercase() }) ?: emptyList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { cur.parentFile?.let { cur = it } },
                        enabled = cur.absolutePath != vm.root.absolutePath
                    ) { Icon(Icons.Default.ArrowUpward, "Up") }
                    Text(
                        cur.absolutePath.removePrefix(vm.root.absolutePath).ifEmpty { "/" },
                        Modifier.weight(1f),
                        fontSize = 12.sp, color = TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Default.CreateNewFolder, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New folder", fontSize = 12.sp)
                    }
                }
                HorizontalDivider(color = Border)
                if (dirs.isEmpty()) {
                    Text("No subfolders", Modifier.padding(16.dp), color = TextSoft, fontSize = 13.sp)
                }
                LazyColumn(Modifier.height(260.dp)) {
                    items(dirs, key = { it.absolutePath }) { d ->
                        Row(
                            Modifier.fillMaxWidth().clickable { cur = d }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, null, tint = PrimaryStrong)
                            Spacer(Modifier.width(12.dp))
                            Text(d.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.ChevronRight, null, tint = TextFaint)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(cur) }) { Text("Select this folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (creating) {
        NameDialog(
            "New folder in ${cur.name.ifEmpty { "Internal" }}", "", "Folder name", "Create",
            onDone = { name ->
                creating = false
                val n = name.trim()
                val t = File(cur, n)
                if (n.isEmpty() || n.contains('/')) {
                    vm.say("Invalid name")
                } else if (t.exists()) {
                    vm.say("Already exists")
                } else if (t.mkdirs()) {
                    cur = t
                } else {
                    vm.say("Could not create folder")
                }
            },
            onDismiss = { creating = false }
        )
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
fun NameDialog(
    title: String, initial: String, label: String, confirm: String,
    onDone: (String) -> Unit, onDismiss: () -> Unit, spoken: String? = null
) {
    var text by remember { mutableStateOf(initial) }
    LaunchedEffect(spoken) { if (spoken != null) text = spoken }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text(label) })
        },
        confirmButton = {
            TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun PinEntryDialog(title: String, verify: (String) -> Boolean, onOk: () -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 17.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it.isDigit() }.take(12); err = false },
                    singleLine = true,
                    label = { Text("PIN") },
                    isError = err,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                if (err) Text("Wrong PIN", color = Danger, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (verify(pin)) onOk() else err = true }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SetPinDialog(onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a PIN") },
        text = {
            Column {
                Text("Used to open locked folders and to show hidden folders (4–12 digits).", fontSize = 12.sp, color = TextSoft)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = a, onValueChange = { a = it.filter { c -> c.isDigit() }.take(12) },
                    singleLine = true, label = { Text("New PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = b, onValueChange = { b = it.filter { c -> c.isDigit() }.take(12) },
                    singleLine = true, label = { Text("Confirm PIN") },
                    isError = b.isNotEmpty() && a != b,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSet(a) }, enabled = a.length >= 4 && a == b) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun DetailsDialog(vm: FileViewModel, f: File, onDismiss: () -> Unit) {
    val size by produceState(if (f.isFile) f.length() else -1L, f) {
        if (f.isDirectory) {
            value = withContext(Dispatchers.IO) { f.walkTopDown().filter { it.isFile }.map { it.length() }.sum() }
        }
    }
    val count by produceState(-1, f) {
        if (f.isDirectory) value = withContext(Dispatchers.IO) { f.list()?.size ?: 0 }
    }
    val rows = listOf(
        "Name" to f.name,
        "Type" to if (f.isDirectory) "Folder" else (FileOps.mime(f)),
        "Path" to f.absolutePath,
        "Size" to if (size >= 0) "${FileOps.size(size)} ($size bytes)" else "Calculating…",
        "Items" to if (f.isDirectory) (if (count >= 0) "$count" else "…") else "—",
        "Created" to FileOps.date(FileOps.entry(f).created),
        "Modified" to FileOps.date(f.lastModified()),
        "Permissions" to buildString {
            append(if (f.canRead()) "Read " else "")
            append(if (f.canWrite()) "Write " else "")
            append(if (f.canExecute()) "Execute" else "")
        }.trim().ifEmpty { "None" },
        "Hidden / Locked" to "${if (vm.isHidden(f)) "Hidden" else "Visible"} / ${if (f.absolutePath in vm.locked) "Locked" else "Unlocked"}"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { (k, v) ->
                    Text(k.uppercase(), fontSize = 10.sp, color = TextFaint, fontWeight = FontWeight.Bold)
                    Text(v, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

// ---------------------------------------------------------------- organizer & apps

@Composable
fun OrganizerScreen(vm: FileViewModel, onChange: () -> Unit, onOrganize: (ExtStat) -> Unit) {
    val total = vm.organizer.sumOf { it.size }
    val files = vm.organizer.sumOf { it.count }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp)
                .clip(RoundedCornerShape(16.dp)).background(Color.White)
                .border(1.dp, Border, RoundedCornerShape(16.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Scanning", fontSize = 10.sp, color = TextFaint, fontWeight = FontWeight.Bold)
                Text(
                    vm.path.absolutePath.removePrefix(vm.root.absolutePath).ifEmpty { "/ (Internal)" },
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text("$files files • ${FileOps.size(total)}", fontSize = 12.sp, color = TextSoft)
            }
            TextButton(onClick = onChange) { Text("Change folder") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text("EXTENSION", Modifier.weight(1f), fontSize = 10.sp, color = TextFaint, fontWeight = FontWeight.Bold)
            Text("COUNT", Modifier.weight(1f), fontSize = 10.sp, color = TextFaint, fontWeight = FontWeight.Bold)
            Text("SIZE", Modifier.weight(1f), fontSize = 10.sp, color = TextFaint, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(96.dp))
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(vm.organizer, key = { it.ext }) { s ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f)) {
                        Text(
                            s.ext.uppercase(),
                            Modifier.clip(CircleShape).background(PrimarySoft).padding(horizontal = 10.dp, vertical = 4.dp),
                            fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PrimaryStrong
                        )
                    }
                    Text("${s.count}", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(FileOps.size(s.size), Modifier.weight(1f), fontSize = 13.sp, color = TextSoft)
                    Pill("Organize", false) { onOrganize(s) }
                }
                HorizontalDivider(color = Border, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

class AppEntry(val label: String, val pkg: String, val icon: ImageBitmap)

@Composable
fun AppDrawer(query: String) {
    val ctx = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(i, 0).map {
                AppEntry(
                    it.loadLabel(pm).toString(), it.activityInfo.packageName,
                    it.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
                )
            }.sortedBy { it.label.lowercase() }
        }
    }
    val list = apps
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val shown = if (query.isBlank()) list else list.filter { it.label.contains(query.trim(), true) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(84.dp),
        contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(shown, key = { it.pkg }) { a ->
            Column(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .clickable { ctx.packageManager.getLaunchIntentForPackage(a.pkg)?.let { ctx.startActivity(it) } }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(a.icon, a.label, Modifier.size(48.dp))
                Spacer(Modifier.height(4.dp))
                Text(a.label, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- permission

@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Bg).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(Primary),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Folder, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
        Spacer(Modifier.height(20.dp))
        Text("File Manager Pro", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "To browse, copy, move and organize your files, the app needs access to all files on your device.",
            textAlign = TextAlign.Center, color = TextSoft
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
            Text("Grant access")
        }
    }
}
