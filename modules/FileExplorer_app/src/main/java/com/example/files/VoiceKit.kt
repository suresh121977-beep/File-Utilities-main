package com.example.files

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.merged.voice.VoiceEngine
import com.merged.voice.VoiceSpeaker
import java.io.File

// =====================================================================================
//  Voice control kit (identical copy lives in both File Manager Pro and File Explorer).
//  1. VoiceParser  - turns what you said into a command (pure logic, no Android)
//  2. VoiceEngine  - (module :VoiceKit) offline Vosk recogniser, keeps listening continuously
//  3. VoiceHost    - Compose-friendly state holder (listening / heard text / next command)
//  4. small Compose pieces: mic button, status card, help dialog
// =====================================================================================

enum class Verb { COPY, MOVE, PICKUP, DROP, PASTE, RENAME, DELETE, OPEN, BOOKMARK, ZIP, UNZIP, BACKUP, SHARE, COPYPATH, DETAILS }

sealed class VCmd {
    /** A verb, optionally aimed at serial numbers ("copy 3 5") or carrying text ("rename 3 to report"). */
    class Do(val verb: Verb, val nums: List<Int> = emptyList(), val arg: String? = null) : VCmd()
    class Num(val n: Int) : VCmd()
    class Select(val nums: List<Int>) : VCmd()
    /** "select jpg", "select a", "select report": tick every item in this list that matches. */
    class SelectMatch(val q: String) : VCmd()
    class Unselect(val nums: List<Int>) : VCmd()
    class Search(val q: String) : VCmd()
    class NewItem(val folder: Boolean, val name: String) : VCmd()
    class Text(val s: String) : VCmd()
    object SelectAll : VCmd()
    object ClearSelection : VCmd()
    object ClearSearch : VCmd()
    object Back : VCmd()
    object Home : VCmd()
    object Refresh : VCmd()
    object Yes : VCmd()
    object No : VCmd()
    object Stop : VCmd()
    object Help : VCmd()
}

class VEvent(val cmd: VCmd, val heard: String) {
    /** Set by whichever screen acts on the event, so a screen created later never replays it. */
    var handled = false
}

object VoiceParser {
    private val ones = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )
    private val homophones = mapOf("to" to 2, "too" to 2, "for" to 4, "won" to 1, "ate" to 8, "tree" to 3)
    private val rangeWords = setOf("to", "through", "thru", "till", "until")
    private val fillers = setOf(
        "and", "number", "numbers", "no", "folder", "folders", "file", "files", "item", "items",
        "serial", "the", "please", "then", "plus", "it", "this", "that", "these", "those", "them",
        "here", "selected"
    )

    private val stopSet = setOf(
        "stop", "stop listening", "mic off", "microphone off", "voice off", "stop voice",
        "exit voice", "quit voice", "turn off voice", "goodbye"
    )
    private val helpSet = setOf("help", "commands", "voice help", "voice commands", "what can i say", "show commands", "help me")
    private val yesSet = setOf(
        "yes", "yeah", "yep", "yup", "confirm", "confirmed", "ok", "okay", "sure", "do it",
        "proceed", "done", "correct", "go ahead", "apply", "save"
    )
    private val noSet = setOf("no", "nope", "cancel", "dismiss", "never mind", "nevermind", "abort", "close")

    private val verbs = mapOf(
        "copy" to Verb.COPY, "duplicate" to Verb.COPY,
        "move" to Verb.MOVE, "cut" to Verb.MOVE,
        "pickup" to Verb.PICKUP, "pick" to Verb.PICKUP, "picked" to Verb.PICKUP, "collect" to Verb.PICKUP,
        "drop" to Verb.DROP, "put" to Verb.DROP, "paste" to Verb.PASTE,
        "delete" to Verb.DELETE, "remove" to Verb.DELETE, "trash" to Verb.DELETE, "erase" to Verb.DELETE,
        "open" to Verb.OPEN, "enter" to Verb.OPEN, "view" to Verb.OPEN, "launch" to Verb.OPEN,
        "bookmark" to Verb.BOOKMARK, "favourite" to Verb.BOOKMARK, "favorite" to Verb.BOOKMARK, "star" to Verb.BOOKMARK,
        "zip" to Verb.ZIP, "compress" to Verb.ZIP, "archive" to Verb.ZIP,
        "unzip" to Verb.UNZIP, "extract" to Verb.UNZIP, "uncompress" to Verb.UNZIP, "decompress" to Verb.UNZIP,
        "backup" to Verb.BACKUP,
        "share" to Verb.SHARE, "send" to Verb.SHARE,
        "details" to Verb.DETAILS, "info" to Verb.DETAILS, "properties" to Verb.DETAILS
    )

    private val extAlias = mapOf("text" to "txt", "word" to "doc", "excel" to "xls")

    private fun norm(raw: String): String =
        raw.lowercase().replace("-", " to ").replace(",", " ")
            .replace(Regex("[^a-z0-9. ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Reads one number at t[i]. Returns (value, tokensUsed) or null. */
    private fun readNum(t: List<String>, i: Int): Pair<Int, Int>? {
        if (i >= t.size) return null
        val w = t[i]
        val digits = w.toIntOrNull()
        if (digits != null) return Pair(digits, 1)
        val tv = tens[w]
        if (tv != null) {
            val nx = t.getOrNull(i + 1)
            val ov = if (nx == null) null else (ones[nx] ?: nx.toIntOrNull())
            return if (ov != null && ov in 1..9) Pair(tv + ov, 2) else Pair(tv, 1)
        }
        val ov = ones[w]
        if (ov != null) return Pair(ov, 1)
        return null
    }

    /** "2 4 6", "3 to 8", "twenty five and thirty" -> list. null when something is not a number. */
    private fun parseNums(t: List<String>, homo: Boolean): List<Int>? {
        val out = ArrayList<Int>()
        var i = 0
        while (i < t.size) {
            val w = t[i]
            if (w in fillers) { i++; continue }
            val a = readNum(t, i)
            if (a != null) {
                val next = i + a.second
                val rw = t.getOrNull(next)
                if (rw != null && rw in rangeWords) {
                    val b = readNum(t, next + 1)
                    if (b != null) {
                        val lo = minOf(a.first, b.first)
                        val hi = maxOf(a.first, b.first)
                        if (hi - lo <= 500) for (k in lo..hi) out.add(k)
                        i = next + 1 + b.second
                        continue
                    }
                }
                out.add(a.first)
                i = next
                continue
            }
            val h = if (homo) homophones[w] else null
            if (h != null) { out.add(h); i++; continue }
            return null
        }
        return out.filter { it in 1..9999 }
    }

    fun parse(raw: String): VCmd {
        val txt = raw.trim()
        var s = norm(txt)
        if (s.isEmpty()) return VCmd.Text(txt)
        s = s.replace("pick up", "pickup").replace("back up", "backup").replace("un zip", "unzip")
            .replace("book mark", "bookmark").replace("re name", "rename")
        // words the recogniser often mishears at the start of a command
        s = s.replace(Regex("^(on zip|and zip|unzipped|unship|on ship|on sheep|unsheep|under zip|and ship|and sheep)\\b"), "unzip")
            .replace(Regex("^(sheep|ship|zeep|zipp|zap|sip|zipped|zib)\\b"), "zip")
            .replace(Regex("^(pace|past|paced|based|paist)\\b"), "paste")
            .replace(Regex("^(renamed|rename it)\\b"), "rename")
        val tok = s.split(" ")
        val first = tok[0]
        val rest = tok.drop(1)

        if (s in stopSet) return VCmd.Stop
        if (s in helpSet) return VCmd.Help
        if (s in yesSet) return VCmd.Yes
        if (s in noSet) return VCmd.No

        // ---- search: "search invoice", "search pdf", "search dot jpg", "find extension txt"
        val sm = Regex("(?i)^\\s*(search|find|filter|look for|show only)\\b\\s*(.*)$").find(txt)
        if (sm != null) {
            var q = sm.groupValues[2].trim().trimEnd('.', '!', '?')
            val ql = q.lowercase()
            if (ql.isEmpty()) return VCmd.Text(txt)
            if (ql in setOf("clear", "reset", "close", "off", "none", "nothing", "cancel", "clear search", "clear filter", "reset search")) {
                return VCmd.ClearSearch
            }
            var prev: String
            do {
                prev = q
                q = q.replace(Regex("(?i)^(for|files?|named|called|name|with|the)\\s+"), "")
            } while (q != prev)
            val em = Regex("(?i)^(extension|ext|type|format|dot)\\s+(.+)$").find(q)
            val fm = Regex("(?i)^([a-z0-9]{2,5})\\s+(files?|documents?)$").find(q)
            q = if (em != null) {
                "." + em.groupValues[2].replace(" ", "").lowercase()
            } else if (fm != null) {
                val e = fm.groupValues[1].lowercase()
                "." + (extAlias[e] ?: e)
            } else {
                q.replace(Regex("(?i)\\s+dot\\s+"), ".")
            }
            if (q.isBlank()) return VCmd.Text(txt)
            return VCmd.Search(q)
        }
        if (s in setOf("clear search", "reset search", "close search", "clear filter", "remove filter", "show all")) return VCmd.ClearSearch

        // ---- selection
        if (s in setOf("select all", "select everything", "select all files", "all", "everything", "choose all")) return VCmd.SelectAll
        if (s in setOf("clear", "clear selection", "deselect all", "unselect all", "select none", "deselect", "unselect", "none")) {
            return VCmd.ClearSelection
        }
        if (first in setOf("select", "choose", "mark", "check", "tick")) {
            if (rest.contains("all")) return VCmd.SelectAll
            val n = parseNums(rest, true)
            if (n == null) {
                val q = rest.joinToString(" ").trim()
                return if (q.isNotEmpty()) VCmd.SelectMatch(q) else VCmd.Text(txt)
            }
            return VCmd.Select(n)
        }
        if (first in setOf("unselect", "deselect", "uncheck", "untick")) {
            val n = parseNums(rest, true) ?: return VCmd.Text(txt)
            return if (n.isEmpty()) VCmd.ClearSelection else VCmd.Unselect(n)
        }

        // ---- navigation
        if (s in setOf("back", "go back", "up", "go up", "parent", "previous", "previous folder", "one level up", "go to parent")) return VCmd.Back
        if (s in setOf("home", "go home", "root", "main", "storage root", "go to home")) return VCmd.Home
        if (s in setOf("refresh", "reload")) return VCmd.Refresh

        // ---- new folder / file
        val nm = Regex("(?i)^\\s*(?:new|create|make|add)\\s+(folder|directory|file)\\b\\s*(?:named|called)?\\s*(.*)$").find(txt)
        if (nm != null) {
            val isFile = nm.groupValues[1].lowercase() == "file"
            return VCmd.NewItem(!isFile, spokenName(nm.groupValues[2]))
        }

        // ---- copy path (must be checked before plain "copy")
        val pm = Regex("^(?:copy )?(?:the |full |file |folder )*(?:path|location)\\b\\s*(.*)$").find(s)
        if (pm != null) {
            val numTok = pm.groupValues[1].split(" ").filter { it.isNotEmpty() }
            return VCmd.Do(Verb.COPYPATH, parseNums(numTok, true) ?: emptyList())
        }

        // ---- rename
        if (first == "rename") {
            val rm = Regex("(?i)^\\s*rename\\s*(.*?)\\s*\\b(?:to|as|with)\\b\\s+(.+)$").find(txt)
            if (rm != null) {
                val numPart = norm(rm.groupValues[1]).split(" ").filter { it.isNotEmpty() }
                val nums = parseNums(numPart, true) ?: emptyList()
                return VCmd.Do(Verb.RENAME, nums, spokenName(rm.groupValues[2]).takeIf { it.isNotBlank() })
            }
            val nums = parseNums(rest, true)
            if (nums != null) return VCmd.Do(Verb.RENAME, nums)
            val nameOnly = spokenName(txt.substringAfter(' ', "")).takeIf { it.isNotBlank() }
            return VCmd.Do(Verb.RENAME, emptyList(), nameOnly)
        }

        // ---- everything else that starts with a verb
        val verb = verbs[first]
        if (verb != null) {
            val wantsAll = rest.contains("all") || rest.contains("everything")
            val r2 = rest.filter { it != "all" && it != "everything" }
            val nums = parseNums(r2, true)
            if (nums != null) return VCmd.Do(verb, nums, if (wantsAll) "all" else null)
            if (verb == Verb.OPEN) {
                val name = txt.substringAfter(' ', "").trim()
                if (name.isNotEmpty()) return VCmd.Do(Verb.OPEN, emptyList(), name)
            }
            return VCmd.Text(txt)
        }

        // ---- bare serial numbers: "5"  or  "3 5 7"
        val loneTo = tok.size == 1 && (first == "to" || first == "too")
        val bare = parseNums(tok, loneTo)
        if (bare != null && bare.isNotEmpty()) {
            return if (bare.size == 1) VCmd.Num(bare[0]) else VCmd.Select(bare)
        }
        return VCmd.Text(txt)
    }
}

/** Cleans a spoken name: "report dot pdf" -> "report.pdf", removes characters files cannot contain. */
fun spokenName(raw: String): String =
    raw.trim().trimEnd('.', '!', '?')
        .replace(Regex("(?i)\\s+dot\\s+"), ".")
        .replace(Regex("[\\\\/:*?\"<>|]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/** If the old item is a file with an extension and the new name has none, keep the old extension. */
fun keepExt(old: File, name: String): String {
    if (!old.isFile || name.contains('.')) return name
    val e = old.extension
    return if (e.isEmpty()) name else "$name.$e"
}

/** "select jpg" -> every .jpg, "select a" -> names starting with a, otherwise names containing the word. */
fun matchPredicate(q0: String): (File) -> Boolean {
    val q = q0.lowercase().replace(Regex("^(starting with|starts with|letter|extension|files?)\\s+"), "").trim().removePrefix(".")
    val exts = setOf("jpg", "jpeg", "png", "gif", "pdf", "doc", "docx", "txt", "xls", "xlsx", "mp3", "mp4", "apk", "zip", "ppt", "pptx")
    return when {
        q.length == 1 -> { f: File -> f.name.lowercase().startsWith(q) }
        q in exts -> { f: File -> f.extension.lowercase() == q }
        else -> { f: File -> f.name.lowercase().contains(q) }
    }
}

fun hasMicPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

// ------------------------------------------------------------------------- state holder

class VoiceHost(context: Context) {
    var on by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
    var heard by mutableStateOf("")
    var event by mutableStateOf<VEvent?>(null)
    var notice by mutableStateOf<String?>(null)

    /** File Explorer only: whether the next "drop" moves (true) or copies (false). */
    var dropMove by mutableStateOf(false)

    /** After a spoken "copy"/"move": saying a folder number opens it and pastes straight in. */
    var quickPaste = false

    /** When true, ticking a file starts voice control and the app asks what to do. */
    var autoAsk = true

    private val main = Handler(Looper.getMainLooper())
    private var lastSpoke = 0L

    private val engine = VoiceEngine(
        context,
        onResult = { alts -> deliver(alts) },
        onPartial = { partial = it },
        onFail = { msg ->
            notice = msg
            on = false
            partial = ""
        }
    )

    private val speaker = VoiceSpeaker(context) { speaking ->
        if (speaking) {
            main.removeCallbacksAndMessages(null)
            engine.setPaused(true)
        } else {
            lastSpoke = System.currentTimeMillis()
            main.postDelayed({ engine.setPaused(false) }, 450)
        }
    }

    fun start(): Boolean {
        if (!engine.start()) {
            notice = "Voice recognition is not available on this phone"
            return false
        }
        on = true
        speak("Listening. Tick a file and say your option.")
        return true
    }

    /** Stops listening. [byUser] = the person tapped stop, so do not restart by itself on the next tick. */
    fun stop(byUser: Boolean = false) {
        if (byUser) autoAsk = false
        engine.stop()
        speaker.stop()
        on = false
        partial = ""
    }

    /** Reads a reply aloud (only while voice control is on). */
    fun speak(text: String) {
        if (!on) return
        lastSpoke = System.currentTimeMillis()
        speaker.speak(text)
    }

    /** Like the Voice File Manager: after you tick files the app asks what to do. */
    fun askSelected(n: Int) {
        if (!on || n <= 0) return
        if (System.currentTimeMillis() - lastSpoke < 4000) return
        speak("What can I do with $n selected? Say your option.")
    }

    fun shutdown() {
        stop()
        speaker.shutdown()
    }

    private fun deliver(alts: List<String>) {
        partial = ""
        val first = alts.firstOrNull { it.isNotBlank() } ?: return
        var chosen: VCmd? = null
        var text = first
        for (a in alts) {
            if (a.isBlank()) continue
            val c = VoiceParser.parse(a)
            if (c !is VCmd.Text) {
                chosen = c
                text = a
                break
            }
        }
        heard = text.trim()
        event = VEvent(chosen ?: VCmd.Text(first.trim()), text.trim())
    }
}

// ------------------------------------------------------------------------- UI pieces

const val VOICE_HELP = """Say a NUMBER
  • folder number → opens it
  • file number → selects it
select 2 4 6   ·   select 3 to 8
select all   ·   clear
select jpg   ·   select a   (tick by type / first letter)

copy / move → say a folder number (opens it and pastes)
   or open a folder and say “paste”
   drop shows the picked files: say numbers (any order),
   “all”, “move” / “copy”, “new folder name” → then “drop”
pickup → collect now, drop later
open   ·   open 5   ·   open downloads
rename → say new name → confirm
rename 3 to report
delete → yes  (or cancel)
bookmark   ·   zip   ·   unzip
backup   ·   share   ·   copy path
The app answers out loud and asks “What can I do?” after you tick a file.
Tip: you can add numbers, e.g. “copy 2 5”, “delete 4”, “share 1 to 3”.

search invoice   ·   search pdf
search dot jpg   ·   clear search
new folder photos

back   ·   home   ·   refresh
help   ·   stop"""

@Composable
fun VoiceMicButton(listening: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = if (listening) Color(0xFFD32F2F) else Color(0xFF1E88E5),
        contentColor = Color.White
    ) { Text(if (listening) "⏹" else "🎤", fontSize = 22.sp) }
}

@Composable
fun VoiceStatusCard(partial: String, heard: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), tonalElevation = 4.dp, shadowElevation = 4.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                if (partial.isNotBlank()) "🎤 $partial" else "🎤 Speak now — e.g. “select one two”, “copy”, “drop”",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (heard.isNotBlank()) {
                Text("Heard: “$heard”", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Round "?" button: opens the voice help prompt. */
@Composable
fun VoiceHelpButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = Color(0xFF546E7A),
        contentColor = Color.White
    ) { Text("?", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

/**
 * Help prompt: what you can say, plus a "Start listening" button so you can begin speaking
 * file commands straight away.
 */
@Composable
fun VoiceHelpDialog(onDismiss: () -> Unit, listening: Boolean = false, onStart: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (listening) "🎤 Listening — speak now" else "🎤 Voice commands") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (listening) "Say a command, for example “select one two”, “copy”, then “drop”."
                    else "Tap “Start listening”, then speak a command for your files.",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
                Text(VOICE_HELP, Modifier.padding(top = 10.dp), fontSize = 14.sp)
            }
        },
        confirmButton = {
            if (onStart != null && !listening) {
                TextButton(onClick = { onStart(); onDismiss() }) { Text("🎤 Start listening") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (onStart != null && !listening) TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/** "drop": shows the picked files with serial numbers; say numbers (any order) or "all", then "drop". */
@Composable
fun DropPanel(
    dest: String,
    items: List<File>,
    chosen: Set<String>,
    move: Boolean,
    onToggle: (File) -> Unit,
    onMode: (Boolean) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Drop into “$dest”") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row {
                    TextButton(onClick = { onMode(true) }) { Text(if (move) "✓ Move" else "Move") }
                    TextButton(onClick = { onMode(false) }) { Text(if (!move) "✓ Copy" else "Copy") }
                }
                Text(
                    "Say the numbers (any order) or “all”. Say “move” or “copy”, or “new folder” and a name. Then say “drop”.",
                    fontSize = 12.sp
                )
                items.forEachIndexed { i, f ->
                    Text(
                        "${i + 1}.  ${if (f.absolutePath in chosen) "☑" else "☐"}  ${f.name}${if (f.isDirectory) "/" else ""}",
                        Modifier.fillMaxWidth().clickable { onToggle(f) }.padding(vertical = 8.dp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDrop) { Text(if (move) "Move here" else "Copy here") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
