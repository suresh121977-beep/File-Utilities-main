package com.example.webgrab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.*
import android.widget.*
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var recBtn: Button
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var chooserCb: ValueCallback<Array<Uri>>? = null
    private var pendingWeb: PermissionRequest? = null
    private var pendingAct: (() -> Unit)? = null
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var videoUri: Uri? = null

    private val prefs by lazy { getSharedPreferences("bm", Context.MODE_PRIVATE) }
    private val vault by lazy {
        EncryptedSharedPreferences.create(
            this, "vault",
            MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    private lateinit var menuPanel: LinearLayout

    private fun btn(t: String, kw: String = "", f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; tag = "$t $kw"
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        setOnClickListener { f() }
    }

    private lateinit var menuSearch: EditText
    private var menuItems: List<Button> = emptyList()
    private lateinit var menuScrollV: ScrollView
    private var visibleMenu: List<Button> = emptyList()
    private var menuSr: android.speech.SpeechRecognizer? = null
    private var voiceOn = false
    private lateinit var micBtn: Button

    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager

    private fun closeMenu() {
        if (!::menuPanel.isInitialized) return
        stopVoiceMenu()
        menuPanel.visibility = android.view.View.GONE
        imm().hideSoftInputFromWindow(menuSearch.windowToken, 0)
    }

    private fun toggleMenu() {
        if (menuPanel.visibility == android.view.View.VISIBLE) { closeMenu(); return }
        menuPanel.visibility = android.view.View.VISIBLE
        menuSearch.setText(""); menuSearch.requestFocus()
        imm().showSoftInput(menuSearch, 0)
    }
    private fun row(vararg b: Button) = HorizontalScrollView(this).apply {
        addView(LinearLayout(this@MainActivity).apply { b.forEach { addView(it) } })
    }

    // ---------- numbered menu search + voice menu ----------
    private val numPrefix = Regex("^\\d+\\.\\s{2}")

    private fun baseText(b: Button) = b.text.toString().replace(numPrefix, "")

    private fun menuHay(b: Button) = ((b.tag as String) + " " + baseText(b)).lowercase()

    private fun filterMenu(q0: String) {
        val q = q0.trim().lowercase()
        val shown = mutableListOf<Button>()
        menuItems.forEach {
            val base = baseText(it)
            val ok = q.length >= 2 && menuHay(it).contains(q)
            if (ok) { shown.add(it); it.text = "${shown.size}.  $base"; it.visibility = android.view.View.VISIBLE }
            else { it.text = base; it.visibility = android.view.View.GONE }
        }
        visibleMenu = shown
        val d = resources.displayMetrics
        val lp = menuScrollV.layoutParams
        lp.height = minOf((shown.size * 56 * d.density).toInt(), (d.heightPixels * 0.45).toInt())
        menuScrollV.layoutParams = lp
    }

    private val numWords = mapOf("one" to 1, "won" to 1, "two" to 2, "to" to 2, "too" to 2, "three" to 3,
        "four" to 4, "for" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)

    private fun toggleVoiceMenu() {
        if (voiceOn) stopVoiceMenu() else ensure(Manifest.permission.RECORD_AUDIO) { startVoiceMenu() }
    }

    private fun startVoiceMenu() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) { toast("Voice recognition is not available on this phone"); return }
        voiceOn = true; micBtn.text = "⏹"
        toast("Listening. Say a menu word like html, pdf or text, then a number")
        listenMenu()
    }

    private fun stopVoiceMenu() {
        voiceOn = false
        if (::micBtn.isInitialized) micBtn.text = "🎤"
        menuSr?.destroy(); menuSr = null
    }

    private fun listenMenu() {
        if (!voiceOn) return
        menuSr?.destroy()
        val r = android.speech.SpeechRecognizer.createSpeechRecognizer(this); menuSr = r
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onError(e: Int) {
                if (voiceOn && (e == 6 || e == 7)) web.postDelayed({ listenMenu() }, 300)
                else { if (voiceOn) toast("Voice stopped (error $e)"); stopVoiceMenu() }
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (t.isNotEmpty()) handleMenuVoice(t)
                if (voiceOn) web.postDelayed({ listenMenu() }, 300)
            }
        })
        r.startListening(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-IN"))
    }

    private fun handleMenuVoice(raw: String) {
        val t = raw.lowercase().replace("h t m l", "html").replace("p d f", "pdf")
            .replace(Regex("[^a-z0-9 ]"), " ").trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return
        val words = t.split(" ")
        if (words.any { it == "help" || it == "commands" }) { showVoiceHelp(); return }
        if (words.any { it == "back" || it == "close" || it == "cancel" || it == "exit" }) { closeMenu(); return }
        val num = words.firstNotNullOfOrNull { it.toIntOrNull() ?: numWords[it] }
        if (num != null && words.size <= 3 && visibleMenu.isNotEmpty()) {
            if (num in 1..visibleMenu.size) {
                visibleMenu[num - 1].performClick()
                toast("Done. Say another number, or say close")
            }
            else toast("Say a number from 1 to ${visibleMenu.size}")
            return
        }
        val tries = listOf(t) + words.filter { it.length >= 2 }.sortedByDescending { it.length }
        val hit = tries.firstOrNull { q -> menuItems.any { menuHay(it).contains(q) } }
        if (hit == null) { toast("No menu found for \"$t\". Tap ? for commands"); return }
        menuPanel.visibility = android.view.View.VISIBLE
        menuSearch.setText(hit)
        toast(if (visibleMenu.size == 1) "Say 1 to open it" else "Say a number from 1 to ${visibleMenu.size}")
    }

    private fun showVoiceHelp() {
        val list = menuItems.joinToString("\n") { "• " + baseText(it) }
        val msg = "Tap 🎤, then say a menu word. A numbered list appears. Say the number to run it.\n\n" +
            "Examples:\n“html” → HTML menus, then “2”\n“pdf” → PDF menus\n“text” or “txt” → Webpage to TXT\n" +
            "“read” → Read aloud / Stop reading\n“record” → audio, screen, video\n“table” → tables to XLSX / CSV\n\n" +
            "Say “back” to close the menu and “help” to see this list. Typing 2 letters in the search box also gives a numbered list.\n\n" +
            "All menus:\n" + list
        AlertDialog.Builder(this).setTitle("Voice commands").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun saveTxt() {
        web.evaluateJavascript("(function(){var s=String(window.getSelection());var t=s.trim()?s:document.body.innerText;return document.title+'\\n'+location.href+'\\n\\n'+t})()") { r ->
            val t = str(r)
            if (t.isBlank()) { toast("No text found on this page"); return@evaluateJavascript }
            val name = host().replace(Regex("[^A-Za-z0-9._-]"), "_") + "_${System.currentTimeMillis() / 1000}.txt"
            try {
                saveToDownloads(name, "text/plain") { it.write(t.toByteArray()) }
                toast("Saved to Downloads: $name")
            } catch (e: Exception) { toast("Save failed: ${e.message}") }
        }
    }

    // ---------- whole website: ZIP of pages, or complete offline copy ----------
    @Volatile private var siteStop = false
    private val skipExt = setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "ico", "bmp", "css", "js", "json", "xml", "pdf", "zip", "rar", "7z", "gz", "tar",
        "apk", "exe", "dmg", "mp3", "mp4", "mkv", "avi", "mov", "wav", "ogg", "webm", "woff", "woff2", "ttf", "otf", "eot", "doc", "docx", "xls", "xlsx",
        "ppt", "pptx", "csv", "txt", "rss", "atom")
    private val tagRe = Regex("<(a|area|link|script|img|source|video|audio|track|input)\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val attrRe = Regex("(\\s)(href|src|data-src|data-original|data-lazy-src|poster|data-poster|srcset|data-srcset)(\\s*=\\s*)([\"'])(.*?)\\4", RegexOption.IGNORE_CASE)
    private val urlRe = Regex("url\\(\\s*(['\"]?)([^'\")]+?)\\1\\s*\\)", RegexOption.IGNORE_CASE)
    private val importRe = Regex("@import\\s+(['\"])(.*?)\\1", RegexOption.IGNORE_CASE)
    private val relAssetRe = Regex("rel\\s*=\\s*[\"']?[^\"'>]*(stylesheet|icon|preload|prefetch|manifest|modulepreload)", RegexOption.IGNORE_CASE)
    private val noLinkRe = Regex("^(javascript|mailto|tel|data|blob|about|sms):", RegexOption.IGNORE_CASE)

    private class Fetched(val url: String, val ct: String, val bytes: ByteArray)

    private fun siteDialog(full: Boolean) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val urlE = EditText(this).apply { setText(web.url ?: ""); hint = "Website address"; setSingleLine(true) }
        val maxE = EditText(this).apply { setText(if (full) "100" else "200"); hint = "Max pages"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val folderC = CheckBox(this).apply { text = "Stay inside this folder only" }
        col.addView(urlE); col.addView(maxE); col.addView(folderC)
        val info = if (full)
            "Saves every page plus its CSS, JavaScript, images, fonts and linked files (also from other servers), with all links fixed for offline use. The copy is kept inside WebGrab (menu: Open offline copy) and a ZIP goes to Downloads. Pages built only by JavaScript may still show little."
        else
            "Saves every page of the site as HTML, with links between pages fixed for offline use. Your logged-in session in this browser is used. Images, CSS and JavaScript are not included."
        AlertDialog.Builder(this).setTitle(if (full) "📴 Complete offline copy" else "🌐 Whole site to ZIP")
            .setMessage(info).setView(col)
            .setPositiveButton("Start") { _, _ ->
                val u = urlE.text.toString().trim().let { if (it.startsWith("http")) it else "https://$it" }
                val max = (maxE.text.toString().toIntOrNull() ?: 100).coerceIn(1, 5000)
                grabSite(u, max, folderC.isChecked, full)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun siteKey(u: java.net.URI): String =
        (u.host ?: "").removePrefix("www.").lowercase() + (u.rawPath ?: "").ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: "")

    private fun siteOk(t: java.net.URI, hostKey: String, prefix: String): Boolean {
        val s = (t.scheme ?: "").lowercase()
        if (s != "http" && s != "https") return false
        if ((t.host ?: return false).removePrefix("www.").lowercase() != hostKey) return false
        val p = (t.path ?: "").ifEmpty { "/" }
        if (!p.startsWith(prefix)) return false
        val ext = p.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return ext !in skipExt
    }

    private fun cleanPath(raw: String): String {
        val parts = raw.split("/").filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { it.replace(Regex("[^A-Za-z0-9._~-]"), "_").take(120) }
        return if (parts.isEmpty()) "index.html" else parts.joinToString("/")
    }

    private fun sitePath(u: java.net.URI): String {
        var p = (u.path ?: "").ifEmpty { "/" }
        if (p.endsWith("/")) p += "index"
        p = p.replace(Regex("\\.(x?html?)$", RegexOption.IGNORE_CASE), "")
        u.rawQuery?.let { p += "_" + Integer.toHexString(it.hashCode()) }
        return cleanPath(p.trimStart('/') + ".html")
    }

    private fun assetPath(u: java.net.URI, ct: String, same: Boolean): String {
        var p = (u.path ?: "").ifEmpty { "/" }
        if (p.endsWith("/")) p += "file"
        val last = p.substringAfterLast('/')
        var ext = if (last.contains('.')) last.substringAfterLast('.') else ""
        var stem = if (ext.isNotEmpty()) p.substring(0, p.length - ext.length - 1) else p
        if (ext.isEmpty() || ext.length > 6) {
            ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(ct.substringBefore(';').trim().lowercase()) ?: "bin"
            stem = p
        }
        u.rawQuery?.let { stem += "_" + Integer.toHexString(it.hashCode()) }
        return cleanPath((if (same) "" else "_ext/" + (u.host ?: "host") + "/") + stem.trimStart('/') + "." + ext)
    }

    private fun relPath(from: String, to: String): String {
        val a = from.split("/").dropLast(1); val b = to.split("/")
        var i = 0
        while (i < a.size && i < b.size - 1 && a[i] == b[i]) i++
        return "../".repeat(a.size - i) + b.drop(i).joinToString("/")
    }

    private fun siteDecode(ct: String, bytes: ByteArray): String {
        val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
            ?: Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(String(bytes, 0, minOf(bytes.size, 3000), Charsets.ISO_8859_1))?.groupValues?.get(1)
        val cset = try { if (cs != null) java.nio.charset.Charset.forName(cs) else Charsets.UTF_8 } catch (e: Exception) { Charsets.UTF_8 }
        return String(bytes, cset)
    }

    private fun siteGet(first: String, ua: String, wantHtml: Boolean, maxBytes: Int): Fetched? {
        var url = first
        repeat(6) {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000; conn.readTimeout = 25000
                conn.setRequestProperty("User-Agent", ua)
                conn.setRequestProperty("Accept", if (wantHtml) "text/html,application/xhtml+xml" else "*/*")
                conn.setRequestProperty("Accept-Language", "en")
                CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    url = java.net.URL(java.net.URL(url), loc).toString()
                    return@repeat
                }
                if (code != 200) return null
                val ct = conn.contentType ?: ""
                if (wantHtml && !ct.lowercase().contains("html")) return null
                if (conn.contentLength > maxBytes) return null
                val bo = java.io.ByteArrayOutputStream()
                conn.inputStream.use { s ->
                    val b = ByteArray(16384)
                    while (true) { val r = s.read(b); if (r < 0) break; bo.write(b, 0, r); if (bo.size() > maxBytes) return null }
                }
                return Fetched(url, ct, bo.toByteArray())
            } finally { conn.disconnect() }
        }
        return null
    }

    /** Walks every link/asset reference in HTML or CSS. f(absoluteUrl, isPageLink) returns a replacement path, or null to leave it unchanged. */
    private fun refsMap(text: String, base: java.net.URI?, css: Boolean, f: (String, Boolean) -> String?): String {
        fun one(raw: String, page: Boolean): String? {
            val h = raw.trim()
            if (base == null || h.isEmpty() || h.startsWith("#") || noLinkRe.containsMatchIn(h)) return null
            val abs = try { base.resolve(h.substringBefore('#').replace(" ", "%20")) } catch (e: Exception) { return null }
            val s = (abs.scheme ?: "").lowercase()
            if (s != "http" && s != "https") return null
            val r = f(abs.toString(), page) ?: return null
            return r + (if (h.contains('#')) "#" + h.substringAfter('#') else "")
        }
        var t = text
        if (!css) {
            t = tagRe.replace(t) { m ->
                val tag = m.groupValues[1].lowercase()
                var attrs = m.groupValues[2]
                val linkOk = tag != "link" || relAssetRe.containsMatchIn(attrs)
                attrs = attrRe.replace(attrs) { a ->
                    val name = a.groupValues[2].lowercase()
                    val page = name == "href" && (tag == "a" || tag == "area")
                    val skip = (name == "href" && !page && !(tag == "link" && linkOk)) || (tag == "a" && name != "href")
                    if (skip) a.value
                    else {
                        val v = a.groupValues[5]
                        val nv = if (name.endsWith("srcset")) v.split(",").joinToString(", ") { part ->
                            val p = part.trim(); val u = p.substringBefore(' ')
                            val r = one(u, false)
                            if (r == null) p else r + p.substring(u.length)
                        } else (one(v, page) ?: v)
                        a.groupValues[1] + a.groupValues[2] + a.groupValues[3] + a.groupValues[4] + nv + a.groupValues[4]
                    }
                }
                if (tag == "link" || tag == "script") {
                    attrs = attrs.replace(Regex("\\sintegrity\\s*=\\s*([\"']).*?\\1", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("\\scrossorigin(\\s*=\\s*([\"']).*?\\2)?", RegexOption.IGNORE_CASE), "")
                }
                "<" + m.groupValues[1] + attrs + ">"
            }
        }
        t = urlRe.replace(t) { m -> val r = one(m.groupValues[2], false); if (r == null) m.value else "url(" + m.groupValues[1] + r + m.groupValues[1] + ")" }
        t = importRe.replace(t) { m -> val r = one(m.groupValues[2], false); if (r == null) m.value else "@import " + m.groupValues[1] + r + m.groupValues[1] }
        return t
    }

    private fun grabSite(startUrl: String, max: Int, folderOnly: Boolean, full: Boolean) {
        val start = try { java.net.URI(startUrl.replace(" ", "%20")).normalize() } catch (e: Exception) { toast("That address is not valid"); return }
        val hostKey = (start.host ?: run { toast("That address is not valid"); return }).removePrefix("www.").lowercase()
        val prefix = if (folderOnly) (start.path ?: "").substringBeforeLast('/', "") + "/" else "/"
        val ua = web.settings.userAgentString
        val pad = (16 * resources.displayMetrics.density).toInt()
        val msg = TextView(this).apply { setPadding(pad, pad, pad, pad); text = "Starting…" }
        siteStop = false
        val dlg = AlertDialog.Builder(this).setTitle("Grabbing $hostKey").setView(msg).setCancelable(false)
            .setNegativeButton("Stop and save") { _, _ -> siteStop = true }.show()
        Thread {
            val stamp = System.currentTimeMillis() / 1000
            val stem = hostKey.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dir = File(cacheDir, "site_$stamp").apply { mkdirs() }
            val outDir = File(filesDir, "offline/${stem}_$stamp")
            val localOf = HashMap<String, String>()
            val used = HashSet<String>()
            val pageRel = LinkedHashMap<String, String>()
            val assetRel = LinkedHashMap<String, String>()
            val queue = java.util.ArrayDeque<String>(); val seen = HashSet<String>()
            val aQueue = java.util.ArrayDeque<String>(); val aSeen = HashSet<String>()
            var firstRel = "index.html"
            val pfx = if (folderOnly) prefix else "/"

            fun unique(p0: String): String {
                var p = p0; var i = 2
                while (!used.add(p)) {
                    val dot = p0.lastIndexOf('.')
                    p = if (dot > 0) p0.substring(0, dot) + "_$i" + p0.substring(dot) else p0 + "_$i"
                    i++
                }
                return p
            }
            fun collect(text: String, base: java.net.URI, css: Boolean) {
                refsMap(text, base, css) { abs, page ->
                    val t = try { java.net.URI(abs) } catch (e: Exception) { null }
                    if (t != null) {
                        if (page && siteOk(t, hostKey, pfx)) {
                            if (seen.add(siteKey(t))) queue.add(abs)
                        } else if (full && aSeen.size < 8000) {
                            val same = (t.host ?: "").removePrefix("www.").lowercase() == hostKey
                            val ext = (t.path ?: "").substringAfterLast('/').substringAfterLast('.', "").lowercase()
                            if (!page || (same && ext in skipExt)) { if (aSeen.add(siteKey(t))) aQueue.add(abs) }
                        }
                    }
                    null
                }
            }

            seen.add(siteKey(start)); queue.add(start.toString())
            var n = 0; var failed = 0
            while (queue.isNotEmpty() && n < max && !siteStop) {
                val u = queue.poll() ?: break
                runOnUiThread { msg.text = "Pages saved: $n of $max\nPages waiting: ${queue.size}\nFailed or skipped: $failed\n\n${u.take(90)}" }
                val got = try { siteGet(u, ua, true, 8_000_000) } catch (e: Exception) { null }
                if (got == null) { failed++; continue }
                val fu = try { java.net.URI(got.url.replace(" ", "%20")) } catch (e: Exception) { failed++; continue }
                if (!siteOk(fu, hostKey, "/") || localOf.containsKey(siteKey(fu))) { failed++; continue }
                val html = siteDecode(got.ct, got.bytes)
                val rel = unique(sitePath(fu))
                try { File(dir, rel).apply { parentFile?.mkdirs() }.writeText(html) } catch (e: Exception) { used.remove(rel); failed++; continue }
                if (pageRel.isEmpty()) firstRel = rel
                localOf[siteKey(fu)] = rel
                try { localOf[siteKey(java.net.URI(u.replace(" ", "%20")))] = rel } catch (e: Exception) { }
                pageRel[rel] = got.url
                n++
                collect(html, fu, false)
                try { Thread.sleep(120) } catch (e: InterruptedException) { break }
            }
            if (pageRel.isEmpty()) {
                dir.deleteRecursively()
                runOnUiThread { dlg.dismiss(); toast("No pages could be fetched. The site may block apps or need JavaScript.") }
                return@Thread
            }

            var totalBytes = 0L; var aDone = 0; var aFail = 0
            while (full && aQueue.isNotEmpty() && !siteStop && totalBytes < 600_000_000L) {
                val au = aQueue.poll() ?: break
                runOnUiThread { msg.text = "Pages saved: $n\nFiles saved: $aDone (failed $aFail)\nFiles waiting: ${aQueue.size}\n\n${au.take(90)}" }
                val g = try { siteGet(au, ua, false, 40_000_000) } catch (e: Exception) { null }
                if (g == null) { aFail++; continue }
                val gu = try { java.net.URI(g.url.replace(" ", "%20")) } catch (e: Exception) { aFail++; continue }
                val same = (gu.host ?: "").removePrefix("www.").lowercase() == hostKey
                val rel = unique(assetPath(gu, g.ct, same))
                try { File(dir, rel).apply { parentFile?.mkdirs() }.writeBytes(g.bytes) } catch (e: Exception) { used.remove(rel); aFail++; continue }
                totalBytes += g.bytes.size
                localOf[siteKey(gu)] = rel
                try { localOf[siteKey(java.net.URI(au.replace(" ", "%20")))] = rel } catch (e: Exception) { }
                assetRel[rel] = g.url; aDone++
                if (g.ct.lowercase().contains("css") || rel.endsWith(".css")) collect(siteDecode(g.ct, g.bytes), gu, true)
            }

            runOnUiThread { msg.text = "Building the offline copy…" }
            val name = stem + (if (full) "_offline_" else "_site_") + stamp + ".zip"
            val headRe = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
            val baseRe = Regex("<base\\b[^>]*>", RegexOption.IGNORE_CASE)
            val meta = "<meta charset=\"utf-8\">"
            try {
                outDir.mkdirs()
                pageRel.forEach { (rel, pageUrl) ->
                    val base = java.net.URI(pageUrl.replace(" ", "%20"))
                    var out = refsMap(File(dir, rel).readText(), base, false) { abs, _ -> localOf[siteKey(java.net.URI(abs))]?.let { relPath(rel, it) } }
                    out = baseRe.replace(out, "")
                    val mh = headRe.find(out)
                    out = if (mh != null) out.substring(0, mh.range.last + 1) + meta + out.substring(mh.range.last + 1) else meta + out
                    File(outDir, rel).apply { parentFile?.mkdirs() }.writeText(out)
                }
                assetRel.forEach { (rel, aUrl) ->
                    val src = File(dir, rel); val dst = File(outDir, rel).apply { parentFile?.mkdirs() }
                    if (rel.endsWith(".css")) {
                        val base = java.net.URI(aUrl.replace(" ", "%20"))
                        dst.writeText(refsMap(src.readText(), base, true) { abs, _ -> localOf[siteKey(java.net.URI(abs))]?.let { relPath(rel, it) } })
                    } else src.copyTo(dst, true)
                }
                File(outDir, "_start.txt").writeText(firstRel)
                File(outDir, "_url_list.txt").writeText((pageRel.entries + assetRel.entries).joinToString("\n") { "${it.key}\t${it.value}" })
                saveToDownloads(name, "application/zip") { os ->
                    ZipOutputStream(os.buffered()).use { z ->
                        outDir.walkTopDown().filter { it.isFile }.forEach { f ->
                            z.putNextEntry(ZipEntry(f.relativeTo(outDir).path.replace('\\', '/')))
                            f.inputStream().use { it.copyTo(z) }
                            z.closeEntry()
                        }
                    }
                }
                runOnUiThread {
                    dlg.dismiss()
                    AlertDialog.Builder(this).setTitle("Done")
                        .setMessage("${pageRel.size} pages" + (if (full) " and ${assetRel.size} files" else "") + " saved.\nZIP: Downloads/$name\nAn offline copy is also kept inside WebGrab.")
                        .setPositiveButton("Open offline") { _, _ -> openOffline(outDir) }.setNegativeButton("Close", null).show()
                }
            } catch (e: Exception) {
                runOnUiThread { dlg.dismiss(); toast("Failed: ${e.message}") }
            }
            dir.deleteRecursively()
        }.start()
    }

    private fun openOffline(dir: File) {
        val start = try { File(dir, "_start.txt").readText().trim() } catch (e: Exception) { "index.html" }
        val f = File(dir, start)
        if (!f.exists()) { toast("Offline copy not found"); return }
        web.settings.allowFileAccess = true
        web.loadUrl(Uri.fromFile(f).toString())
        closeMenu()
    }

    private fun offlineDialog() {
        val base = File(filesDir, "offline")
        val dirs = base.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (dirs.isEmpty()) { toast("No offline copies yet. Use \"Complete site offline copy\" first."); return }
        AlertDialog.Builder(this).setTitle("Offline copies (tap to open)")
            .setItems(dirs.map { it.name }.toTypedArray()) { _, i -> openOffline(dirs[i]) }
            .setNeutralButton("Delete all") { _, _ -> base.deleteRecursively(); toast("Deleted") }
            .setNegativeButton("Close", null).show()
    }

    // ---------- JS bridge: TTS + clipboard ----------
    inner class Bridge(private val wv: WebView? = null) {
        // ---- Tampermonkey-style helpers used by user scripts (GM_xmlhttpRequest, GM_download, GM_setValue ...)
        @JavascriptInterface fun gmXhr(id: Int, spec: String) = gmRequest(wv, id, spec)
        @JavascriptInterface fun gmDownload(u: String, n: String) { runOnUiThread { downloadDM(u, n) } }
        @JavascriptInterface fun gmGet(k: String): String = prefs.getString("gm_$k", "") ?: ""
        @JavascriptInterface fun gmSet(k: String, v: String) { prefs.edit().putString("gm_$k", v).apply() }
        @JavascriptInterface fun gmDel(k: String) { prefs.edit().remove("gm_$k").apply() }
        @JavascriptInterface fun speak(id: Int, text: String, lang: String, rate: Double, pitch: Double) {
            runOnUiThread {
                if (!ttsReady) return@runOnUiThread
                try { tts.language = if (lang.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(lang) } catch (e: Exception) {}
                tts.setSpeechRate(rate.toFloat()); tts.setPitch(pitch.toFloat())
                ttsPending.incrementAndGet()
                tts.speak(text, TextToSpeech.QUEUE_ADD, null, id.toString())
            }
        }
        @JavascriptInterface fun saveStart(n: String) = pageStart(n)
        @JavascriptInterface fun saveChunk(c: String) = pageChunk(c)
        @JavascriptInterface fun saveDone() = pageDone()
        @JavascriptInterface fun tablesDone(j: String) = autoDone(j)
        @JavascriptInterface fun note(t: String) { toast(t) }
        @JavascriptInterface fun paste(): String =
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
        @JavascriptInterface fun srStart(lang: String, cont: Boolean, interim: Boolean) { runOnUiThread { startSr(lang, cont, interim) } }
        @JavascriptInterface fun srStop() { runOnUiThread { stopSr() } }
        // streaming file save (used by Chat Grabber for big ZIPs): open, write base64 pieces, close
        @JavascriptInterface fun fileOpen(n: String, m: String) = fileBegin(n, m)
        @JavascriptInterface fun fileWrite(c: String) = filePart(c)
        @JavascriptInterface fun fileClose() = fileEnd()
        @JavascriptInterface fun blobStart(n: String, m: String) = blobBegin(n, m)
        @JavascriptInterface fun blobChunk(c: String) = blobPart(c)
        @JavascriptInterface fun blobDone() = blobEnd()
        @JavascriptInterface fun cancel() { runOnUiThread { tts.stop() } }
        @JavascriptInterface fun copy(t: String) {
            runOnUiThread {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("web", t))
                toast("Copied")
            }
        }
    }

    private val polyfill = """(function(){if(window.__wg||!window.WG)return;window.__wg=1;var Q={},n=0;
function U(t){this.text=t||'';this.lang='';this.rate=1;this.pitch=1;this.volume=1;this.voice=null}
window.SpeechSynthesisUtterance=U;
window.__wgEv=function(t,i){var u=Q[i];if(!u)return;var f=u['on'+t];if(f)try{f({type:t})}catch(e){};if(t=='end')delete Q[i]};
window.speechSynthesis={speaking:false,pending:false,paused:false,
speak:function(u){var i=++n;Q[i]=u;WG.speak(i,u.text||'',u.lang||'',u.rate||1,u.pitch||1)},
cancel:function(){WG.cancel()},pause:function(){},resume:function(){},
getVoices:function(){return[]},addEventListener:function(){},removeEventListener:function(){}};
function selText(){var a=document.activeElement;if(a&&(a.tagName=='TEXTAREA'||a.tagName=='INPUT')&&a.selectionStart!=null&&a.selectionEnd>a.selectionStart)return a.value.substring(a.selectionStart,a.selectionEnd);return String(window.getSelection())}
var oe=document.execCommand?document.execCommand.bind(document):null;
document.execCommand=function(c,u,v){if(String(c).toLowerCase()=='copy'){var t=selText();if(t){WG.copy(t);return true}}return oe?oe(c,u,v):false};
try{Object.defineProperty(navigator,'clipboard',{configurable:true,value:{
writeText:function(t){WG.copy(String(t));return Promise.resolve()},
readText:function(){return Promise.resolve(WG.paste())},
write:function(items){try{var it=items[0];var ty=it.types.indexOf('text/plain')>=0?'text/plain':it.types[0];return it.getType(ty).then(function(b){return b.text()}).then(function(t){WG.copy(t)})}catch(e){return Promise.resolve()}},
read:function(){return Promise.resolve([])}}})}catch(e){}
function SR(){this.lang='en-US';this.continuous=false;this.interimResults=false;this.maxAlternatives=1}
SR.prototype.start=function(){window.__wgSRi=this;WG.srStart(this.lang||'en-US',!!this.continuous,!!this.interimResults)};
SR.prototype.stop=function(){WG.srStop()};SR.prototype.abort=SR.prototype.stop;
SR.prototype.addEventListener=function(t,f){this.__l=this.__l||{};this.__l[t]=(this.__l[t]||[]).concat([f])};
SR.prototype.removeEventListener=function(){};
window.SpeechRecognition=window.webkitSpeechRecognition=SR;
window.__wgSR=function(t,p){var r=window.__wgSRi;if(!r)return;var ev={type:t};
if(t=='start')r.__res=[];
if(t=='result'){var o=JSON.parse(p);var it=[{transcript:o.text,confidence:0.9}];it.item=function(i){return this[i]};it.isFinal=o.final;
r.__res=r.__res||[];var rs=r.__res.slice();if(o.final){r.__res.push(it);rs=r.__res.slice()}else rs.push(it);
rs.item=function(i){return this[i]};ev.results=rs;ev.resultIndex=Math.max(0,rs.length-1)}
if(t=='error')ev.error=p;
var f=r['on'+t];if(f)try{f.call(r,ev)}catch(e){}
((r.__l&&r.__l[t])||[]).forEach(function(g){try{g.call(r,ev)}catch(e){}})};
window.__wgSaveUrl=function(h,n){fetch(h).then(function(r){return r.blob()}).then(function(b){var f=new FileReader();f.onload=function(){var d=f.result,s=d.substring(d.indexOf(',')+1);WG.blobStart(n||'download',b.type||'');for(var i=0;i<s.length;i+=600000)WG.blobChunk(s.substring(i,i+600000));WG.blobDone()};f.readAsDataURL(b)}).catch(function(e){WG.note('Save failed: '+e)})};
var oc2=HTMLAnchorElement.prototype.click;
HTMLAnchorElement.prototype.click=function(){if(this.hasAttribute('download')&&/^(blob|data):/.test(this.href)){window.__wgSaveUrl(this.href,this.getAttribute('download'));return}oc2.call(this)};
document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a[download]');if(a&&/^(blob|data):/.test(a.href)){e.preventDefault();window.__wgSaveUrl(a.href,a.getAttribute('download'))}},true);
})()"""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) = ev("start", id)
                    override fun onDone(id: String) { ttsPending.updateAndGet { maxOf(0, it - 1) }; ev("end", id) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String) { ttsPending.updateAndGet { maxOf(0, it - 1) }; ev("end", id) }
                })
            }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        urlBox = EditText(this).apply {
            hint = "Search or enter URL"; setSingleLine(); inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
        }
        val bar = LinearLayout(this).apply {
            addView(urlBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(btn("Go") { go(urlBox.text.toString()); closeMenu() })
            addView(Button(this@MainActivity).apply { text = "☰"; setOnClickListener { toggleMenu() } })
        }
        recBtn = btn("🎙 Rec", "record audio microphone voice") { toggleAudio() }
        screenBtn = btn("⏺ Screen", "record screen video") { toggleScreen() }
        rotBtn = btn("⟳ Auto", "rotate orientation landscape portrait") { rotate() }
        val items = listOf(
            btn("← Back", "previous page") { if (web.canGoBack()) web.goBack() },
            rotBtn,
            btn("📁 Open HTML file", "local html mic") { pickHtml() },
            btn("🧹 Clear history", "cache cookies browsing data") { clearData() },
            btn("🔊 Read aloud", "tts speak") { readPage() },
            btn("🔇 Stop reading", "tts stop speech") { stopSpeech() },
            btn("📄 Webpage to PDF (Save as PDF)", "print webpage save pdf") { savePdf() },
            btn("📝 Webpage to TXT", "text txt plain save webpage") { saveTxt() },
            btn("★ Add bookmark") { addBookmark() },
            btn("★ Bookmarks list") { showBookmarks() },
            btn("⬇ Export CSV", "bookmarks passwords backup") { exportDialog() },
            btn("🔑 Save password", "login") { savePassword() },
            btn("🔑 Fill password", "login autofill") { fillPassword() },
            btn("🎬 Media (images, video, audio)", "download gallery photos") { scanMedia() },
            btn("📎 Files (PDF, XLS, ZIP)", "download documents") { scanFiles() },
            btn("📊 Tables to XLSX / CSV", "table excel pages screener") { scanTables() },
            recBtn,
            btn("🎥 Record video", "camera") { recordVideo() },
            btn("🧺 Table basket", "collect pages xlsx") { basketDialog() },
            btn("🌐 Translate page", "language hindi telugu") { translateDialog() },
            screenBtn,
            btn("🧩 User scripts", "extensions tampermonkey js") { scriptsDialog() },
            btn("💾 Save whole page (HTML)", "offline download") { savePageDialog() },
            btn("🌐 Whole site to ZIP (pages only)", "website site crawl all pages zip html") { siteDialog(false) },
            btn("📴 Complete site offline copy (CSS, JS, images)", "complete offline copy mirror website css javascript images all files zip html") { siteDialog(true) },
            btn("📂 Open offline copy", "offline view saved site mirror") { offlineDialog() },
            btn("🔊 Record device audio (WAV)", "internal sound original quality") { toggleDeviceAudio() },
            btn("📦 Export page data (backup JSON)", "backup saved data localstorage") { exportPageData() },
            btn("📥 Import page data (restore)", "backup restore json") { pickImport() },
            btn("🛠 Fix page layout", "shrink zoom resize reset") { fixLayout() })
        val menuList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.forEach { it.visibility = android.view.View.GONE; menuList.addView(it, MATCH_PARENT, WRAP_CONTENT) }
        val menuScroll = ScrollView(this).apply { addView(menuList) }
        menuScrollV = menuScroll; menuItems = items
        menuSearch = EditText(this).apply {
            hint = "🔍 Type 2 letters to find a menu"; setSingleLine()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(c: CharSequence?, st: Int, ct: Int, af: Int) {}
                override fun onTextChanged(c: CharSequence?, st: Int, bf: Int, ct: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { filterMenu(e.toString()) }
            })
        }
        menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE
            micBtn = Button(this@MainActivity).apply { text = "🎤"; minWidth = 0; minimumWidth = 0; setOnClickListener { toggleVoiceMenu() } }
            val helpBtn = Button(this@MainActivity).apply { text = "?"; minWidth = 0; minimumWidth = 0; setOnClickListener { showVoiceHelp() } }
            addView(LinearLayout(this@MainActivity).apply {
                addView(menuSearch, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(micBtn, WRAP_CONTENT, WRAP_CONTENT)
                addView(helpBtn, WRAP_CONTENT, WRAP_CONTENT)
            }, MATCH_PARENT, WRAP_CONTENT)
            addView(menuScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0))
        }

        statusBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        root.addView(bar, MATCH_PARENT, WRAP_CONTENT)
        root.addView(statusBar, MATCH_PARENT, WRAP_CONTENT)
        root.addView(menuPanel, MATCH_PARENT, WRAP_CONTENT)
        holder = FrameLayout(this)
        tabStrip = LinearLayout(this)
        root.addView(HorizontalScrollView(this).apply { addView(tabStrip) }, MATCH_PARENT, WRAP_CONTENT)
        loadBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = android.view.View.GONE
        }
        root.addView(loadBar, MATCH_PARENT, (4 * resources.displayMetrics.density).toInt())
        root.addView(holder, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        newTab("https://www.google.com")
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
    }

    private fun ev(t: String, id: String) = runOnUiThread {
        if (id.isNotEmpty() && id.all { it.isDigit() }) web.evaluateJavascript("window.__wgEv&&__wgEv('$t',$id)", null)
        statusHandler.postDelayed({ refreshStatus() }, 300)
    }

    override fun onBackPressed() { if (customView != null) hideCustom() else if (web.canGoBack()) web.goBack() else super.onBackPressed() }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
    private fun host() = Uri.parse(web.url ?: "").host ?: "page"
    private fun str(s: String?): String = (JSONTokener(s ?: "null").nextValue() as? String) ?: ""
    private fun unq(s: String?): String = str(s).ifEmpty { "[]" }

    private fun go(s: String) {
        val t = s.trim()
        web.loadUrl(when {
            t.startsWith("http") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        })
    }

    // ---------- permissions ----------
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun ensure(p: String, action: () -> Unit) {
        if (granted(p)) action() else { pendingAct = action; requestPermissions(arrayOf(p), 10) }
    }

    private fun handleWebPerm(r: PermissionRequest) {
        val need = mutableListOf<String>()
        for (res in r.resources) {
            if (res == PermissionRequest.RESOURCE_AUDIO_CAPTURE) need.add(Manifest.permission.RECORD_AUDIO)
            if (res == PermissionRequest.RESOURCE_VIDEO_CAPTURE) need.add(Manifest.permission.CAMERA)
        }
        val missing = need.filter { !granted(it) }
        if (missing.isEmpty()) r.grant(r.resources)
        else { pendingWeb = r; requestPermissions(missing.toTypedArray(), 11) }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val ok = res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }
        if (code == 11) {
            val r = pendingWeb; pendingWeb = null
            if (ok) r?.grant(r.resources) else r?.deny()
        } else if (code == 10) {
            val a = pendingAct; pendingAct = null
            if (ok) a?.invoke() else toast("Permission denied")
        }
    }

    // ---------- activity results ----------
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        when (req) {
            1 -> { chooserCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data)); chooserCb = null }
            2 -> if (res == RESULT_OK && data?.data != null) {
                val html = contentResolver.openInputStream(data.data!!)?.use { String(it.readBytes()) } ?: ""
                if (!granted(Manifest.permission.RECORD_AUDIO)) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 13)
                val inj = "<script>" + polyfill + "</script>"
                val hd = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
                val m = hd.find(html)
                val html2 = if (m != null) html.substring(0, m.range.last + 1) + inj + html.substring(m.range.last + 1) else inj + html
                var nm = "page"
                contentResolver.query(data.data!!, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) { val k = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (k >= 0) nm = c.getString(k) }
                }
                val base = "https://h" + Integer.toHexString(nm.hashCode()) + ".localhost/"
                web.loadDataWithBaseURL(base, html2, "text/html", "utf-8", null)
            }
            3 -> if (res == RESULT_OK && data?.data != null) {
                val u = data.data!!
                val code = contentResolver.openInputStream(u)?.use { String(it.readBytes()) } ?: ""
                if (!hasScriptCode(code)) {
                    toast("This file has only the header (// ==UserScript== lines) and no code, so it cannot do anything. Copy the whole script.")
                    return
                }
                var name = u.lastPathSegment ?: "script"
                contentResolver.query(u, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i) }
                }
                val set = prefs.getStringSet("us", emptySet())!!.toMutableSet(); set.add(name)
                prefs.edit().putStringSet("us", set).putString("us_$name", code).apply()
                toast("Script added: $name"); web.reload()
            }
            5 -> if (res == RESULT_OK && data != null) {
                startForegroundService(Intent(this, ScreenRecordService::class.java)
                    .putExtra("code", res).putExtra("data", data).putExtra("mic", screenMic).putExtra("mode", screenMode))
                screenBtn.text = "⏹ Stop screen"
            }
            6 -> if (res == RESULT_OK && data?.data != null) {
                val text = contentResolver.openInputStream(data.data!!)?.use { String(it.readBytes()) } ?: ""
                web.evaluateJavascript("(function(){try{var d=JSON.parse(" + JSONObject.quote(text) +
                    ");for(var k in d)localStorage.setItem(k,d[k]);location.reload()}catch(e){WG.note('Import failed: not a valid backup')}})()", null)
                toast("Data imported, reloading page")
            }
            4 -> {
                if (res == RESULT_OK) toast("Video saved to Movies/WebGrab")
                else videoUri?.let { contentResolver.delete(it, null, null) }
            }
        }
    }

    // ---------- open local HTML ----------
    private fun pickHtml() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/html", "application/xhtml+xml"))
        }, 2)
    }

    // ---------- clear history ----------
    private fun clearData() {
        val items = arrayOf("History & cache", "Cookies & site data (logs you out)")
        val chk = booleanArrayOf(true, false)
        AlertDialog.Builder(this).setTitle("Clear browsing data")
            .setMultiChoiceItems(items, chk) { _, i, c -> chk[i] = c }
            .setPositiveButton("Clear") { _, _ ->
                if (chk[0]) { web.clearHistory(); web.clearCache(true); web.clearFormData() }
                if (chk[1]) { CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData() }
                toast("Cleared")
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- read aloud ----------
    private fun readPage() {
        if (ttsReady && tts.isSpeaking) { tts.stop(); toast("Stopped"); return }
        web.evaluateJavascript("(window.getSelection().toString()||document.body.innerText).slice(0,4000)") { r ->
            val t = str(r)
            if (t.isNotEmpty() && ttsReady) { ttsPending.set(1); tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "page") }
        }
    }

    // ---------- downloads ----------
    private fun downloadDM(u: String, name: String? = null) {
        try {
            val r = DownloadManager.Request(Uri.parse(u))
            CookieManager.getInstance().getCookie(u)?.let { r.addRequestHeader("Cookie", it) }
            r.addRequestHeader("User-Agent", web.settings.userAgentString)
            web.url?.let { r.addRequestHeader("Referer", it) }
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                name?.replace(Regex("[\\/:*?\"<>|]"), "_")?.trim()?.takeIf { it.isNotEmpty() } ?: URLUtil.guessFileName(u, null, null))
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
        } catch (e: Exception) { toast("Failed: ${e.message}") }
    }

    // ---------- fast direct downloader (parallel) ----------
    private val dlPool = java.util.concurrent.Executors.newFixedThreadPool(4)
    private val dlTotal = java.util.concurrent.atomic.AtomicInteger(0)
    private val dlDone = java.util.concurrent.atomic.AtomicInteger(0)
    private val dlFail = java.util.concurrent.atomic.AtomicInteger(0)

    private fun download(u: String) {
        val ua = web.settings.userAgentString
        val ref = web.url
        dlTotal.incrementAndGet()
        dlPool.execute {
            try { fetchToDownloads(u, ua, ref) }
            catch (e: Exception) { dlFail.incrementAndGet(); toast("Failed: ${u.substringAfterLast('/').take(40)} (${e.message})") }
            finally {
                val d = dlDone.incrementAndGet()
                if (d == dlTotal.get()) {
                    val f = dlFail.get()
                    toast("Downloads finished: ${d - f} saved to Downloads" + if (f > 0) ", $f failed" else "")
                    dlTotal.set(0); dlDone.set(0); dlFail.set(0)
                }
            }
        }
    }

    private fun fetchToDownloads(u: String, ua: String, ref: String?) {
        var url = java.net.URL(u)
        var conn: java.net.HttpURLConnection
        var hops = 0
        while (true) {
            conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 30000; conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Accept", "*/*")
            CookieManager.getInstance().getCookie(url.toString())?.let { conn.setRequestProperty("Cookie", it) }
            ref?.let { conn.setRequestProperty("Referer", it) }
            val code = conn.responseCode
            val loc = conn.getHeaderField("Location")
            if (code in 300..399 && loc != null && hops++ < 6) { url = java.net.URL(url, loc); conn.disconnect(); continue }
            if (code !in 200..299) { conn.disconnect(); throw Exception("HTTP $code") }
            break
        }
        val mime = conn.contentType?.substringBefore(';')?.trim()
        val name = URLUtil.guessFileName(url.toString(), conn.getHeaderField("Content-Disposition"), mime)
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
        }
        val out = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw Exception("cannot create file")
        try {
            contentResolver.openOutputStream(out)!!.use { o -> conn.inputStream.use { it.copyTo(o, 65536) } }
        } catch (e: Exception) { contentResolver.delete(out, null, null); throw e }
        conn.disconnect()
    }

    private val mediaJs = """(function(){var o=[];function a(u,t){if(u&&u.indexOf('data:')!=0&&u.indexOf('blob:')!=0)o.push(t+'|'+new URL(u,location.href).href)}
document.querySelectorAll('img').forEach(function(e){a(e.currentSrc||e.src,'IMG')});
document.querySelectorAll('video,audio,source').forEach(function(e){a(e.currentSrc||e.src,e.tagName=='AUDIO'?'AUDIO':'VIDEO')});
document.querySelectorAll('a[href]').forEach(function(e){if(/\.(mp4|webm|mkv|mp3|m4a|wav|ogg|jpg|jpeg|png|gif|webp)(\?|${'$'})/i.test(e.href))a(e.href,'MEDIA')});
try{function cl(u){try{var x=new URL(u);x.searchParams.delete('bytestart');x.searchParams.delete('byteend');return x.href}catch(e){return u}}
var h=document.documentElement.innerHTML.replace(/\\\//g,'/').replace(/\\u0025/g,'%').replace(/&amp;/g,'&');
(h.match(/https?:\/\/[^"'\s<>\\]+?\.mp4[^"'\s<>\\]*/g)||[]).forEach(function(u){a(cl(u),'VIDEO')});
performance.getEntriesByType('resource').forEach(function(e){if(/\.mp4/.test(e.name))a(cl(e.name),'VIDEO')})}catch(x){}
return JSON.stringify(Array.from(new Set(o)))})()"""

    private val filesJs = """(function(){var o=[];
document.querySelectorAll('a[href]').forEach(function(e){var m=e.href.match(/\.(pdf|xlsx?|csv|zip|rar|7z|docx?|pptx?|txt|json|epub|apk)(\?|#|${'$'})/i);
if(m)o.push(m[1].toUpperCase()+'|'+e.href)});
return JSON.stringify(Array.from(new Set(o)))})()"""

    private fun scanFiles() = scan(filesJs, "files")

    private fun scan(js: String, what: String) {
        web.evaluateJavascript(js) { res ->
            val arr = JSONArray(unq(res))
            if (arr.length() == 0) { toast("No $what found on this page"); return@evaluateJavascript }
            val items = Array(arr.length()) { i ->
                val s = arr.getString(i)
                "[${s.substringBefore("|")}] " + s.substringAfter("|").substringAfterLast("/").take(60)
            }
            val checked = BooleanArray(items.size)
            AlertDialog.Builder(this).setTitle("Found ${items.size} $what")
                .setMultiChoiceItems(items, checked) { _, i, c -> checked[i] = c }
                .setPositiveButton("Download selected") { _, _ ->
                    var n = 0
                    for (i in items.indices) if (checked[i]) { download(arr.getString(i).substringAfter("|")); n++ }
                    toast("Downloading $n files")
                }
                .setNeutralButton("Download all") { _, _ ->
                    for (i in items.indices) download(arr.getString(i).substringAfter("|"))
                    toast("Downloading ${items.size} files")
                }
                .setNegativeButton("Close", null).show()
        }
    }

    // ---------- tables -> XLSX / basket / all pages ----------
    private val basket = mutableListOf<List<List<String>>>()

    private val tablesJs = "JSON.stringify(Array.from(document.querySelectorAll('table')).map(function(t){return Array.from(t.rows).map(function(r){return Array.from(r.cells).map(function(c){return (c.innerText||c.textContent||'').replace(/\\s+/g,' ').trim()})})}))"

    private fun rowsOf(rows: JSONArray) = (0 until rows.length()).map { r ->
        val cells = rows.getJSONArray(r)
        (0 until cells.length()).map { cells.getString(it) }
    }

    private fun scanTables() {
        web.evaluateJavascript(tablesJs) { res ->
            val tj = JSONArray(unq(res))
            val all = (0 until tj.length()).map { t -> t to rowsOf(tj.getJSONArray(t)) }.filter { it.second.isNotEmpty() }
            if (all.isEmpty()) { toast("No tables found"); return@evaluateJavascript }
            val labels = Array(all.size) { i ->
                val t = all[i].second
                "Table ${i + 1}: ${t.size} rows x ${t.maxOf { it.size }} cols - " + (t[0].firstOrNull() ?: "").take(25)
            }
            val chk = BooleanArray(all.size) { true }
            AlertDialog.Builder(this).setTitle("Tap to select tables")
                .setMultiChoiceItems(labels, chk) { _, i, c -> chk[i] = c }
                .setPositiveButton("Save XLSX") { _, _ ->
                    val sel = all.filterIndexed { i, _ -> chk[i] }.map { it.second }
                    if (sel.isEmpty()) toast("Nothing selected") else saveXlsx(sel)
                }
                .setNeutralButton("More…") { _, _ -> moreTables(all, chk) }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun moreTables(all: List<Pair<Int, List<List<String>>>>, chk: BooleanArray) {
        val sel = all.filterIndexed { i, _ -> chk[i] }
        AlertDialog.Builder(this).setTitle("More")
            .setItems(arrayOf("Save selected as CSV files", "➕ Add selected to basket (collect page by page)",
                "📄 All pages → one XLSX (auto, first selected table)")) { _, w ->
                if (sel.isEmpty()) { toast("Nothing selected"); return@setItems }
                when (w) {
                    0 -> {
                        sel.forEachIndexed { i, p ->
                            saveToDownloads("${host()}_table${i + 1}_${System.currentTimeMillis() / 1000}.csv", "text/csv") { o ->
                                o.write(csv(p.second).toByteArray())
                            }
                        }
                        toast("CSV files saved to Downloads")
                    }
                    1 -> { sel.forEach { basket.add(it.second) }; toast("Basket: ${basket.size} table(s). Go to next page and add again.") }
                    2 -> { toast("Collecting all pages... please wait"); web.evaluateJavascript(autoJs + "(" + sel[0].first + ")", null) }
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun mergeTables(ts: List<List<List<String>>>): List<List<String>> {
        val out = mutableListOf<List<String>>()
        ts.forEachIndexed { i, t ->
            t.forEachIndexed { r, row ->
                if (i > 0 && r == 0 && row == ts[0].firstOrNull()) return@forEachIndexed
                out.add(row)
            }
        }
        return out
    }

    private fun basketDialog() {
        if (basket.isEmpty()) { toast("Basket empty. Tables → More → Add to basket on each page."); return }
        AlertDialog.Builder(this).setTitle("Basket: ${basket.size} tables, ${basket.sumOf { it.size }} rows")
            .setItems(arrayOf("Save XLSX - all merged in one sheet", "Save XLSX - one sheet per table", "Clear basket")) { _, i ->
                when (i) {
                    0 -> saveXlsx(listOf(mergeTables(basket)))
                    1 -> saveXlsx(basket.toList())
                    2 -> { basket.clear(); toast("Basket cleared") }
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun saveXlsx(sheets: List<List<List<String>>>, quiet: Boolean = false) {
        saveToDownloads("${host()}_${System.currentTimeMillis() / 1000}.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") { buildXlsx(sheets, it) }
        if (!quiet) toast("Saved XLSX: ${sheets.size} sheet(s), ${sheets.sumOf { it.size }} rows (Downloads)")
    }

    fun autoDone(j: String) { runOnUiThread { autoDoneUi(j) } }

    private fun autoDoneUi(j: String) {
        val o = JSONObject(j)
        val rows = rowsOf(o.getJSONArray("rows"))
        if (rows.isEmpty()) { toast("No rows found"); return }
        saveXlsx(listOf(rows), true)
        toast("Saved XLSX: ${o.getInt("pages")} page(s) merged, ${rows.size} rows (Downloads)")
    }

    private val autoJs = """(async function(ti){
function norm(c){return (c.textContent||'').replace(/\s+/g,' ').trim()}
function rows(t){return Array.from(t.rows).map(function(r){return Array.from(r.cells).map(norm)})}
function pick(doc){var ts=doc.querySelectorAll('table');if(ts[ti]&&ts[ti].rows.length)return rows(ts[ti]);var best=[];ts.forEach(function(t){var r=rows(t);if(r.length>best.length)best=r});return best}
function sleep(ms){return new Promise(function(r){setTimeout(r,ms)})}
var first=pick(document);var hdr=JSON.stringify(first[0]||[]);var out=[],pages=0;
function add(rs){rs.forEach(function(r,i){if(pages>0&&i==0&&JSON.stringify(r)==hdr)return;out.push(r)});pages++}
var u=new URL(location.href),nums=[];
document.querySelectorAll('a[href]').forEach(function(a){try{var x=new URL(a.href,location.href);if(x.pathname==u.pathname){var p=x.searchParams.get('page');if(p&&/^\d+${'$'}/.test(p))nums.push(+p)}}catch(e){}});
if(nums.length){
 var cur=+(u.searchParams.get('page')||1);var mx=Math.min(Math.max(Math.max.apply(null,nums),cur),300);var m={};m[cur]=first;
 for(var p=1;p<=mx;p++){if(p==cur)continue;u.searchParams.set('page',p);WG.note('Page '+p+' of '+mx);
  try{var r=await fetch(u.href,{credentials:'include'});var d=new DOMParser().parseFromString(await r.text(),'text/html');m[p]=pick(d)}catch(e){}}
 for(var q=1;q<=mx;q++)if(m[q])add(m[q]);
}else{
 add(first);
 var pg=document.querySelector('[class*=paginat]');
 if(pg){var seen={};seen[JSON.stringify(first)]=1;
  var btns=Array.from(pg.querySelectorAll('a,button,li')).filter(function(e){return /^\d+${'$'}/.test(e.textContent.trim())});var done={};
  for(var b of btns){var k=b.textContent.trim();if(done[k])continue;done[k]=1;b.click();await sleep(1800);
   var c2=pick(document);var key=JSON.stringify(c2);if(seen[key])continue;seen[key]=1;add(c2);WG.note('Page '+k)}}
}
WG.tablesDone(JSON.stringify({pages:pages,rows:out}));
})"""

    private fun saveToDownloads(name: String, mime: String, write: (OutputStream) -> Unit) {
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
        }
        val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return
        contentResolver.openOutputStream(u)?.use { write(it) }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")

    private fun col(i: Int): String { var n = i; var s = ""; while (n >= 0) { s = ('A' + n % 26) + s; n = n / 26 - 1 }; return s }

    private fun buildXlsx(sheets: List<List<List<String>>>, out: OutputStream) {
        val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val hdr = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        ZipOutputStream(out).use { z ->
            fun put(n: String, c: String) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", hdr + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } +
                "</Types>")
            put("_rels/.rels", hdr + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"$rel/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            put("xl/workbook.xml", hdr + "<workbook xmlns=\"$ns\" xmlns:r=\"$rel\"><sheets>" +
                sheets.indices.joinToString("") { "<sheet name=\"Table${it + 1}\" sheetId=\"${it + 1}\" r:id=\"rId${it + 1}\"/>" } +
                "</sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", hdr + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"$rel/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
                "</Relationships>")
            sheets.forEachIndexed { si, rows ->
                val sb = StringBuilder(hdr + "<worksheet xmlns=\"$ns\"><sheetData>")
                rows.forEachIndexed { r, cells ->
                    sb.append("<row r=\"${r + 1}\">")
                    cells.forEachIndexed { c, v ->
                        sb.append("<c r=\"${col(c)}${r + 1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>")
                    }
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                put("xl/worksheets/sheet${si + 1}.xml", sb.toString())
            }
        }
    }

    // ---------- recording ----------
    private fun toggleAudio() {
        if (recorder != null) stopAudio() else ensure(Manifest.permission.RECORD_AUDIO) { startAudio() }
    }

    private fun startAudio() {
        try {
            val f = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a"); recFile = f
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100); r.setAudioEncodingBitRate(192000); r.setAudioChannels(1)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            recorder = r; recBtn.text = "⏹ Stop"; toast("Recording audio..."); refreshStatus()
        } catch (e: Exception) { toast("Record failed: ${e.message}"); recorder = null }
    }

    private fun stopAudio() {
        val r = recorder ?: return
        try { r.stop() } catch (e: Exception) {}
        r.release(); recorder = null; recBtn.text = "🎙 Rec"; refreshStatus()
        val f = recFile ?: return
        val v = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/WebGrab")
        }
        val u = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v)
        if (u != null) {
            contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            toast("Saved: Music/WebGrab/${f.name}")
        }
        f.delete()
    }

    private fun recordVideo() = ensure(Manifest.permission.CAMERA) {
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "video_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WebGrab")
        }
        val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
        if (u != null) {
            videoUri = u
            val i = Intent(MediaStore.ACTION_VIDEO_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, u)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { startActivityForResult(i, 4) } catch (e: Exception) { toast("No camera app found") }
        }
    }

    // ---------- user scripts (Tampermonkey-style) ----------
    private var webUa = ""

    private class UserScript(
        val name: String, val version: String, val code: String,
        val matches: List<Regex>, val excludes: List<Regex>
    ) {
        fun appliesTo(url: String): Boolean {
            if (excludes.any { it.containsMatchIn(url) }) return false
            return matches.isEmpty() || matches.any { it.containsMatchIn(url) }
        }
    }

    // @match / @include pattern -> regex. Wildcard patterns such as https-star-youtube.com work, and so do regex includes.
    private fun globToRegex(p0: String): Regex? = try {
        val p = p0.trim()
        if (p == "*" || p == "<all_urls>") {
            Regex(".*")
        } else if (p.length > 2 && p.startsWith("/") && p.endsWith("/")) {
            Regex(p.substring(1, p.length - 1), RegexOption.IGNORE_CASE)
        } else {
            val sb = StringBuilder("^")
            var rest = p
            if (rest.startsWith("*://")) { sb.append("https?://"); rest = rest.substring(4) }
            if (rest.startsWith("*.")) { sb.append("([^/]*\\.)?"); rest = rest.substring(2) }
            for (ch in rest) { if (ch == '*') sb.append(".*") else sb.append(Regex.escape(ch.toString())) }
            sb.append("$")
            Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    } catch (e: Exception) { null }

    private fun parseScript(file: String, code: String): UserScript {
        val meta = HashMap<String, MutableList<String>>()
        var inHeader = false
        for (line in code.lines()) {
            val t = line.trim()
            if (t.contains("==UserScript==")) { inHeader = true; continue }
            if (t.contains("==/UserScript==")) break
            if (!inHeader) continue
            val m = Regex("^//\\s*@([\\w:-]+)\\s*(.*)$").find(t) ?: continue
            meta.getOrPut(m.groupValues[1]) { mutableListOf() }.add(m.groupValues[2].trim())
        }
        val inc = (meta["match"].orEmpty() + meta["include"].orEmpty()).mapNotNull { globToRegex(it) }
        val exc = (meta["exclude"].orEmpty() + meta["exclude-match"].orEmpty()).mapNotNull { globToRegex(it) }
        return UserScript(meta["name"]?.firstOrNull() ?: file, meta["version"]?.firstOrNull() ?: "1", code, inc, exc)
    }

    /** True when the file has real code, not only "// ..." header lines. */
    private fun hasScriptCode(code: String): Boolean =
        code.lines().any { val t = it.trim(); t.isNotEmpty() && !t.startsWith("//") }

    private val gmShim = """
var GM_info={scriptHandler:'WebGrab',version:'1',script:{name:__NAME__,version:__VER__}};
var unsafeWindow=window;
window.__wgGmCb=window.__wgGmCb||{};
if(!window.__wgGm)window.__wgGm=function(id,j){var c=window.__wgGmCb[id];if(c){delete window.__wgGmCb[id];c(JSON.parse(j))}};
function GM_addStyle(css){var s=document.createElement('style');s.textContent=css;(document.head||document.documentElement).appendChild(s);return s}
function GM_xmlhttpRequest(d){
 var id=(window.__wgGmN=(window.__wgGmN||0)+1);
 window.__wgGmCb[id]=function(r){
  if(r.error){var e={error:r.error,status:0,responseText:'',context:d.context};
   if(/timed out/i.test(r.error)&&d.ontimeout)d.ontimeout(e);else if(d.onerror)d.onerror(e);return}
  var x={status:r.status,statusText:r.statusText,responseText:r.responseText,response:r.responseText,finalUrl:r.finalUrl,responseHeaders:r.responseHeaders,readyState:4,context:d.context};
  if(d.responseType=='json'){try{x.response=JSON.parse(r.responseText)}catch(e){x.response=null}}
  try{if(d.onreadystatechange)d.onreadystatechange(x);if(d.onload)d.onload(x)}catch(e){WG.note('Script error: '+e)}
  try{if(d.onloadend)d.onloadend(x)}catch(e){}
 };
 WG.gmXhr(id,JSON.stringify({method:d.method||'GET',url:String(d.url),headers:d.headers||{},data:d.data==null?null:String(d.data),timeout:d.timeout||0}));
 return {abort:function(){}}}
function GM_download(a,b){var u=typeof a=='string'?a:a.url,n=typeof a=='string'?b:a.name;WG.gmDownload(String(u),String(n||''))}
function GM_setValue(k,v){WG.gmSet(__ID__+':'+k,JSON.stringify(v))}
function GM_getValue(k,def){var s=WG.gmGet(__ID__+':'+k);if(!s)return def;try{return JSON.parse(s)}catch(e){return def}}
function GM_deleteValue(k){WG.gmDel(__ID__+':'+k)}
function GM_openInTab(u){window.open(u,'_blank')}
function GM_setClipboard(t){WG.copy(String(t))}
function GM_registerMenuCommand(){}
function GM_log(){}
var GM={info:GM_info,addStyle:function(c){return Promise.resolve(GM_addStyle(c))},xmlHttpRequest:GM_xmlhttpRequest,download:GM_download,openInTab:GM_openInTab,
 setClipboard:function(t){GM_setClipboard(t);return Promise.resolve()},registerMenuCommand:GM_registerMenuCommand,
 setValue:function(k,v){GM_setValue(k,v);return Promise.resolve()},getValue:function(k,d){return Promise.resolve(GM_getValue(k,d))},deleteValue:function(k){GM_deleteValue(k);return Promise.resolve()}};
var __wael=window.__wgPael||window.addEventListener,__wdael=document.addEventListener;
window.addEventListener=function(t,f,o){if(t==='load'&&document.readyState==='complete'){setTimeout(function(){try{f.call(window,new Event('load'))}catch(e){}},0);return}return __wael.call(window,t,f,o)};
document.addEventListener=function(t,f,o){if(t==='DOMContentLoaded'&&document.readyState!=='loading'){setTimeout(function(){try{f.call(document,new Event('DOMContentLoaded'))}catch(e){}},0);return}return __wdael.call(document,t,f,o)};
"""

    private fun wrapScript(s: UserScript): String {
        val id = Integer.toHexString(s.name.hashCode())
        val shim = gmShim.replace("__NAME__", JSONObject.quote(s.name)).replace("__VER__", JSONObject.quote(s.version))
            .replace("__ID__", JSONObject.quote(id))
        return "(function(){var K='__wgus_" + id + "';if(window[K])return;window[K]=1;\n" + shim +
            "\ntry{\n" + s.code + "\n}catch(e){WG.note('Script ' + " + JSONObject.quote(s.name) +
            " + ' error: ' + (e&&e.message||e))}finally{window.addEventListener=__wael;document.addEventListener=__wdael}\n})();"
    }

    private fun runScripts(v: WebView = web, url: String? = v.url) {
        val u = url ?: return
        if (!u.startsWith("http") ) return
        for (n in prefs.getStringSet("us", emptySet())!!) {
            val code = prefs.getString("us_$n", null) ?: continue
            val sc = parseScript(n, code)
            if (!sc.appliesTo(u)) continue
            v.evaluateJavascript(wrapScript(sc), null)
        }
    }

    /** GM_xmlhttpRequest: a real cross-site request made by the app (no CORS limits), answer goes back to the page. */
    private fun gmRequest(wv: WebView?, id: Int, spec: String) {
        Thread {
            val out = JSONObject()
            try {
                val sp = JSONObject(spec)
                val url = sp.getString("url")
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                val method = sp.optString("method", "GET").uppercase()
                conn.requestMethod = method
                conn.connectTimeout = 20000
                conn.readTimeout = if (sp.optInt("timeout", 0) > 0) sp.optInt("timeout") else 60000
                conn.instanceFollowRedirects = true
                val hs = sp.optJSONObject("headers")
                var hasUa = false
                var hasCookie = false
                if (hs != null) {
                    hs.keys().forEach { k ->
                        if (k.equals("User-Agent", true)) hasUa = true
                        if (k.equals("Cookie", true)) hasCookie = true
                        conn.setRequestProperty(k, hs.optString(k))
                    }
                }
                if (!hasUa && webUa.isNotEmpty()) conn.setRequestProperty("User-Agent", webUa)
                if (!hasCookie) CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
                val body = if (sp.isNull("data")) null else sp.optString("data")
                if (body != null && method != "GET" && method != "HEAD") {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                val sb = StringBuilder()
                conn.headerFields.forEach { (k, v) -> if (k != null) sb.append(k).append(": ").append(v.joinToString(", ")).append("\r\n") }
                out.put("status", code).put("statusText", conn.responseMessage ?: "").put("responseText", text)
                    .put("finalUrl", conn.url.toString()).put("responseHeaders", sb.toString())
            } catch (e: java.net.SocketTimeoutException) {
                out.put("error", "timed out")
            } catch (e: Exception) {
                out.put("error", e.message ?: "network error")
            }
            runOnUiThread {
                (wv ?: web).evaluateJavascript("window.__wgGm&&__wgGm($id," + JSONObject.quote(out.toString()) + ")", null)
            }
        }.start()
    }

    private fun scriptsDialog() {
        val names = prefs.getStringSet("us", emptySet())!!.toList()
        AlertDialog.Builder(this).setTitle("User scripts (tap to remove)")
            .setItems(names.map { "✖ $it" }.toTypedArray()) { _, i ->
                val set = prefs.getStringSet("us", emptySet())!!.toMutableSet(); set.remove(names[i])
                prefs.edit().putStringSet("us", set).remove("us_${names[i]}").apply(); toast("Removed")
            }
            .setPositiveButton("Add .js file") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 3)
            }.setNegativeButton("Close", null).show()
    }

    // ---------- bookmarks ----------
    private fun addBookmark() {
        val set = prefs.getStringSet("bm", emptySet())!!.toMutableSet()
        set.add((web.title ?: host()) + "|" + web.url)
        prefs.edit().putStringSet("bm", set).apply(); toast("Bookmarked")
    }

    private fun showBookmarks() {
        val list = prefs.getStringSet("bm", emptySet())!!.toList()
        if (list.isEmpty()) { toast("No bookmarks"); return }
        AlertDialog.Builder(this).setTitle("Bookmarks")
            .setItems(list.map { it.substringBefore("|") }.toTypedArray()) { _, i -> web.loadUrl(list[i].substringAfterLast("|")) }
            .setNeutralButton("Clear all") { _, _ -> prefs.edit().remove("bm").apply() }
            .setNegativeButton("Close", null).show()
    }

    // ---------- password vault (encrypted) ----------
    private fun savePassword() {
        val user = EditText(this).apply { hint = "Username / email" }
        val pass = EditText(this).apply { hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0); addView(user); addView(pass) }
        AlertDialog.Builder(this).setTitle("Save login for ${host()}").setView(l)
            .setPositiveButton("Save") { _, _ ->
                vault.edit().putString(host(), user.text.toString() + "\n" + pass.text.toString()).apply(); toast("Saved securely")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun fillPassword() {
        val v = vault.getString(host(), null) ?: run { toast("No saved login for ${host()}"); return }
        val u = JSONObject.quote(v.substringBefore("\n")); val p = JSONObject.quote(v.substringAfter("\n"))
        web.evaluateJavascript("""(function(){var p=document.querySelector('input[type=password]');if(!p)return;
var f=p.form||document;var t=f.querySelector('input[type=email],input[type=text]');
function s(e,v){e.focus();e.value=v;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}))}
if(t)s(t,$u);s(p,$p)})()""", null)
    }

    // ---------- export bookmarks / passwords to CSV ----------
    private fun csv(rows: List<List<String>>) = "\uFEFF" + rows.joinToString("\n") { r ->
        r.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }
    }

    private fun exportDialog() {
        AlertDialog.Builder(this).setTitle("Export to CSV (Downloads)")
            .setItems(arrayOf("Bookmarks", "Saved passwords (plain text!)")) { _, i ->
                if (i == 0) {
                    val rows = listOf(listOf("title", "url")) + prefs.getStringSet("bm", emptySet())!!.map {
                        listOf(it.substringBeforeLast("|"), it.substringAfterLast("|"))
                    }
                    saveToDownloads("bookmarks_${System.currentTimeMillis() / 1000}.csv", "text/csv") { o -> o.write(csv(rows).toByteArray()) }
                    toast("Bookmarks saved: ${rows.size - 1}")
                } else {
                    AlertDialog.Builder(this).setTitle("Warning")
                        .setMessage("The CSV will contain your passwords unencrypted. Keep the file safe and delete it after use.")
                        .setPositiveButton("Export") { _, _ ->
                            val rows = listOf(listOf("site", "username", "password")) + vault.all.map { (k, v) ->
                                val s = v.toString(); listOf(k, s.substringBefore("\n"), s.substringAfter("\n"))
                            }
                            saveToDownloads("passwords_${System.currentTimeMillis() / 1000}.csv", "text/csv") { o -> o.write(csv(rows).toByteArray()) }
                            toast("Passwords saved: ${rows.size - 1}")
                        }.setNegativeButton("Cancel", null).show()
                }
            }.setNegativeButton("Close", null).show()
    }

    // ---------- save whole page as offline HTML ----------
    private var pageBuf = StringBuilder()
    private var pageName = "page"

    private val pageJs = """(async function(withFiles){
function b64(b){return new Promise(function(r){var f=new FileReader();f.onload=function(){r(f.result)};f.onerror=function(){r(null)};f.readAsDataURL(b)})}
var cache={};
function get(u,max,ms,text){var k=u+'|'+(!!text);if(cache[k])return cache[k];
cache[k]=(async function(){var c=new AbortController();var t=setTimeout(function(){c.abort()},ms);
try{var r=await fetch(u,{credentials:'include',signal:c.signal});if(!r.ok)return null;
if(text)return await r.text();var b=await r.blob();if(b.size>max)return null;return await b64(b)}catch(e){return null}finally{clearTimeout(t)}})();return cache[k]}
async function pool(a,fn,n){var i=0;async function w(){while(i<a.length){var x=a[i++];try{await fn(x)}catch(e){}}}
await Promise.all(Array.from({length:Math.min(n,a.length)},w))}
var base=location.href;var doc=document.documentElement.cloneNode(true);
var links=Array.from(doc.querySelectorAll('link[rel=stylesheet]'));
var imgs=Array.from(doc.querySelectorAll('img'));
var anchors=Array.from(doc.querySelectorAll('a[href]'));
WG.note('Saving page: '+links.length+' styles, '+imgs.length+' images...');
await pool(links,async function(l){var h;try{h=new URL(l.getAttribute('href'),base).href}catch(e){return}
var t=await get(h,0,8000,true);
if(t==null){l.setAttribute('href',h);return}
t=t.replace(/url\(([^)]+)\)/g,function(m,p){p=p.trim().replace(/^['"]|['"]${'$'}/g,'');if(p.indexOf('data:')==0)return m;try{return 'url('+new URL(p,h).href+')'}catch(e){return m}});
var s=document.createElement('style');s.textContent=t;l.replaceWith(s)},6);
await pool(imgs,async function(i){var s=i.getAttribute('src');if(!s||s.indexOf('data:')==0)return;
var h=new URL(s,base).href;var x=await get(h,8e6,8000);i.setAttribute('src',x||h);i.removeAttribute('srcset')},8);
doc.querySelectorAll('script').forEach(function(s){s.remove()});
var fl=[];
anchors.forEach(function(a){var h0=a.getAttribute('href');
if(!h0||h0[0]=='#'||h0.indexOf('javascript')==0||h0.indexOf('data:')==0)return;
var h;try{h=new URL(h0,base).href}catch(e){return}
a.setAttribute('href',h);
var isFile=h.indexOf('blob:')==0||a.hasAttribute('download')||/\.(pdf|xlsx?|csv|zip|docx?|pptx?|txt|json|png|jpe?g|gif|webp|mp3|mp4|py|md|html?)(\?|#|${'$'})/i.test(h);
if(withFiles&&isFile)fl.push([a,h])});
if(withFiles&&fl.length){WG.note('Embedding '+fl.length+' files...');
await pool(fl,async function(p){var x=await get(p[1],30e6,25000);
if(x){p[0].setAttribute('href',x);if(!p[0].hasAttribute('download'))p[0].setAttribute('download',p[1].split('?')[0].split('/').pop()||'file')}},4)}
var hd=doc.querySelector('head');if(hd)hd.insertAdjacentHTML('afterbegin','<meta charset="utf-8">');
var html='<!DOCTYPE html>\n<!-- saved from '+base+' -->\n'+doc.outerHTML;
WG.saveStart(document.title||location.hostname);
for(var p=0;p<html.length;p+=400000)WG.saveChunk(html.substring(p,p+400000));
WG.saveDone();
})"""

    private fun savePageDialog() {
        AlertDialog.Builder(this).setTitle("Save page as offline HTML")
            .setItems(arrayOf("Page + images", "Page + images + attached files (PDF, XLSX, CSV, ZIP, blob downloads)")) { _, i ->
                toast("Saving page... please wait")
                web.evaluateJavascript(pageJs + "(" + (i == 1) + ")", null)
            }.setNegativeButton("Cancel", null).show()
    }

    fun pageStart(n: String) { pageBuf = StringBuilder(); pageName = n }
    fun pageChunk(c: String) { pageBuf.append(c) }
    fun pageDone() {
        val name = pageName.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(60).trim().ifEmpty { "page" } +
            "_${System.currentTimeMillis() / 1000}.html"
        try {
            saveToDownloads(name, "text/html") { it.write(pageBuf.toString().toByteArray()) }
            runOnUiThread { toast("Saved to Downloads: $name") }
        } catch (e: Exception) { runOnUiThread { toast("Save failed: ${e.message}") } }
        pageBuf = StringBuilder()
    }

    // ---------- tabs ----------
    private val tabs = mutableListOf<WebView>()
    private lateinit var holder: FrameLayout
    private lateinit var loadBar: ProgressBar
    private lateinit var tabStrip: LinearLayout

    private fun makeWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportMultipleWindows(true)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        settings.setNeedInitialFocus(false)
        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(" Version/4.0", "")
        settings.javaScriptCanOpenWindowsAutomatically = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webUa = settings.userAgentString
        addJavascriptInterface(Bridge(this), "WG")
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, u: String, f: android.graphics.Bitmap?) {
                v.evaluateJavascript(polyfill, null)
                if (v === web && ttsReady) tts.stop()
            }
            override fun onPageFinished(v: WebView, u: String) {
                if (v === web) urlBox.setText(u)
                v.evaluateJavascript(polyfill, null)
                runScripts(v, u)
                refreshTabs()
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(r: PermissionRequest) { runOnUiThread { handleWebPerm(r) } }
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                chooserCb?.onReceiveValue(null); chooserCb = cb
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, p.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }
                return try { startActivityForResult(pick, 1); true }
                catch (e: Exception) { chooserCb = null; false }
            }
            override fun onCreateWindow(v: WebView, d: Boolean, g: Boolean, msg: android.os.Message): Boolean {
                val nw = newTab(null)
                (msg.obj as WebView.WebViewTransport).webView = nw
                msg.sendToTarget(); return true
            }
            override fun onCloseWindow(w: WebView) { closeTab(w) }
            override fun onProgressChanged(v: WebView, p: Int) {
                if (v === web && ::loadBar.isInitialized) {
                    loadBar.progress = p
                    loadBar.visibility = if (p in 1..99) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
            override fun onShowCustomView(v: android.view.View, cb: CustomViewCallback) { showCustom(v, cb) }
            override fun onHideCustomView() { hideCustom() }
        }
        setDownloadListener { u, _, cd, mime, _ -> downloadSmart(u, cd, mime) }
    }

    private fun newTab(url: String?): WebView {
        val w = makeWebView()
        tabs.add(w); selectTab(w)
        if (url != null) w.loadUrl(url)
        return w
    }

    private fun selectTab(w: WebView) {
        holder.removeAllViews(); holder.addView(w, MATCH_PARENT, MATCH_PARENT)
        web = w; urlBox.setText(w.url ?: ""); refreshTabs()
    }

    private fun closeTab(w: WebView) {
        val i = tabs.indexOf(w); if (i < 0) return
        tabs.removeAt(i)
        (w.parent as? android.view.ViewGroup)?.removeView(w)
        w.destroy()
        if (tabs.isEmpty()) { newTab("https://www.google.com"); return }
        if (w === web) selectTab(tabs[minOf(i, tabs.size - 1)]) else refreshTabs()
    }

    private fun refreshTabs() {
        if (!::tabStrip.isInitialized) return
        tabStrip.removeAllViews()
        tabStrip.addView(Button(this).apply {
            text = "＋"; textSize = 20f; minWidth = 0; minimumWidth = 0
            setOnClickListener { newTab("https://www.google.com") }
        })
        tabs.forEachIndexed { i, t ->
            tabStrip.addView(Button(this).apply {
                text = (if (t === web) "● " else "") + (t.title ?: "Tab ${i + 1}").take(14)
                isAllCaps = false
                setOnClickListener { selectTab(t) }
            })
            tabStrip.addView(Button(this).apply {
                text = "✕"; minWidth = 0; minimumWidth = 0
                setOnClickListener { closeTab(t) }
            })
        }
    }

    private fun fixLayout() {
        window.decorView.requestApplyInsets()
        holder.removeAllViews(); holder.addView(web, MATCH_PARENT, MATCH_PARENT)
        holder.requestLayout(); web.requestLayout()
        web.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null)
    }

    private fun applyImmersive(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (on) {
                c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(android.view.WindowInsets.Type.systemBars())
            } else c.show(android.view.WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on) (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            else android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // ---------- stop speech ----------
    private fun stopSpeech() {
        ttsPending.set(0)
        if (ttsReady) tts.stop()
        web.evaluateJavascript("window.speechSynthesis&&speechSynthesis.cancel()", null)
        toast("Speech stopped")
    }

    // ---------- translate ----------
    private val langs = linkedMapOf("English" to "en", "Hindi" to "hi", "Telugu" to "te", "Tamil" to "ta",
        "Kannada" to "kn", "Malayalam" to "ml", "Marathi" to "mr", "Bengali" to "bn", "Gujarati" to "gu",
        "Urdu" to "ur", "Arabic" to "ar", "Spanish" to "es", "French" to "fr", "German" to "de",
        "Chinese" to "zh-CN", "Japanese" to "ja", "Russian" to "ru", "Portuguese" to "pt")

    private fun translateDialog() {
        AlertDialog.Builder(this).setTitle("Translate page")
            .setItems(arrayOf("Translate in page (works when logged in)", "Google proxy (public pages, more reliable)")) { _, m ->
                val names = langs.keys.toList()
                AlertDialog.Builder(this).setTitle("Translate to")
                    .setItems(names.toTypedArray()) { _, i -> translate(langs[names[i]]!!, m == 1) }.show()
            }.setNegativeButton("Cancel", null).show()
    }

    private val translateJs = """(function(c){var h=location.hostname;
document.cookie='googtrans=/auto/'+c+';path=/';document.cookie='googtrans=/auto/'+c+';path=/;domain='+h;
var el=document.getElementById('google_translate_element');
if(!el){el=document.createElement('div');el.id='google_translate_element';el.style.cssText='position:fixed;bottom:0;left:0;z-index:2147483647;background:#fff;padding:2px';document.body.appendChild(el)}
window.googleTranslateElementInit=function(){new google.translate.TranslateElement({pageLanguage:'auto',autoDisplay:false},'google_translate_element')};
var s=document.createElement('script');s.src='https://translate.google.com/translate_a/element.js?cb=googleTranslateElementInit';document.body.appendChild(s)})"""

    private fun translate(code: String, proxy: Boolean) {
        if (proxy) {
            val u = Uri.parse(web.url ?: return)
            val h = (u.host ?: return).replace("-", "--").replace(".", "-") + ".translate.goog"
            val q = (u.encodedQuery?.let { "$it&" } ?: "") + "_x_tr_sl=auto&_x_tr_tl=$code&_x_tr_hl=$code"
            web.loadUrl("https://$h${u.encodedPath ?: "/"}?$q")
        } else {
            web.evaluateJavascript(translateJs + "('" + code + "')", null)
            toast("Translating... a language box also appears at bottom-left")
        }
    }

    // ---------- screen recording ----------
    private lateinit var screenBtn: Button
    private var screenMic = false

    private fun toggleScreen() {
        if (ScreenRecordService.active) {
            stopService(Intent(this, ScreenRecordService::class.java)); screenBtn.text = "⏺ Screen"; return
        }
        AlertDialog.Builder(this).setTitle("Screen recording").setMessage("Record microphone audio too?")
            .setPositiveButton("With mic") { _, _ -> ensure(Manifest.permission.RECORD_AUDIO) { askProjection(true) } }
            .setNegativeButton("No audio") { _, _ -> askProjection(false) }
            .setNeutralButton("Cancel", null).show()
    }

    private fun askProjection(mic: Boolean, mode: String = "screen") {
        screenMic = mic; screenMode = mode
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 5)
    }

    override fun onResume() {
        super.onResume()
        statusHandler.removeCallbacks(statusTick); statusHandler.post(statusTick)
    }

    override fun onPause() {
        stopVoiceMenu()
        statusHandler.removeCallbacks(statusTick)
        super.onPause()
    }

    override fun onDestroy() {
        if (ttsReady) tts.shutdown()
        super.onDestroy()
    }

    // ---------- media gallery with previews ----------
    private var galleryDlg: Dialog? = null

    inner class GalBridge {
        @JavascriptInterface fun dl(json: String) {
            val a = JSONArray(json)
            runOnUiThread {
                for (i in 0 until a.length()) download(a.getString(i))
                toast("Downloading ${a.length()} file(s)")
            }
        }
        @JavascriptInterface fun close() { runOnUiThread { galleryDlg?.dismiss() } }
    }

    private val galleryHtml = """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>
body{margin:0;font-family:sans-serif;background:#f1f1f1;color:#111}
#bar{position:sticky;top:0;background:#fff;padding:8px;box-shadow:0 1px 4px #0004;z-index:5}
button{padding:8px 10px;margin:2px;border:1px solid #888;border-radius:8px;background:#fff;font-size:14px;color:#111}
button.on{background:#1a73e8;color:#fff;border-color:#1a73e8}
#g{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:8px;padding:8px}
.c{background:#fff;border-radius:8px;overflow:hidden;position:relative;border:3px solid transparent}
.c.s{border-color:#1a73e8}
.c img,.c video{width:100%;height:120px;object-fit:cover;background:#ccc;display:block}
.c audio{width:100%;margin-top:30px}
.c .n{font-size:11px;padding:4px;word-break:break-all}
.c input{position:absolute;top:6px;left:6px;width:24px;height:24px;z-index:2}
</style></head><body>
<div id="bar"><div id="fl"></div>
<button onclick="sa(1)">Select all</button><button onclick="sa(0)">None</button>
<button id="ds" onclick="dls()">Download selected (0)</button>
<button onclick="dla()">Download all shown</button><button onclick="G.close()">Close</button></div>
<div id="g"></div>
<script>
var D=__DATA__,S={},F='ALL';
function url(s){return s.substring(s.indexOf('|')+1)}
function nm(u){try{return decodeURIComponent(u.split('?')[0].split('/').pop())||u}catch(e){return u}}
function ty(s){var t=s.split('|')[0],u=url(s).split('?')[0].toLowerCase();
if(t=='MEDIA'){if(/\.(mp4|webm|mkv)${'$'}/.test(u))return 'VIDEO';if(/\.(mp3|m4a|wav|ogg)${'$'}/.test(u))return 'AUDIO';return 'IMG'}return t}
function fl(){var h='';['ALL','IMG','VIDEO','AUDIO'].forEach(function(k){var n=D.filter(function(s){return k=='ALL'||ty(s)==k}).length;
h+='<button class="'+(F==k?'on':'')+'" onclick="F=\''+k+'\';fl();draw()">'+k+' ('+n+')</button>'});document.getElementById('fl').innerHTML=h}
function draw(){var g=document.getElementById('g');g.innerHTML='';
D.forEach(function(s,i){var t=ty(s),u=url(s);if(F!='ALL'&&t!=F)return;
var c=document.createElement('div');c.className='c'+(S[i]?' s':'');var m;
if(t=='IMG'){m=document.createElement('img');m.loading='lazy';m.src=u}
else if(t=='VIDEO'){m=document.createElement('video');m.src=u;m.controls=true;m.preload='metadata';m.setAttribute('playsinline','')}
else{m=document.createElement('audio');m.src=u;m.controls=true;m.preload='none'}
var k=document.createElement('input');k.type='checkbox';k.checked=!!S[i];
k.onchange=function(){S[i]=k.checked;c.className='c'+(k.checked?' s':'');cnt()};
var n=document.createElement('div');n.className='n';n.textContent='['+t+'] '+nm(u);
c.appendChild(m);c.appendChild(k);c.appendChild(n);g.appendChild(c)});cnt()}
function cnt(){var n=0;for(var i in S)if(S[i])n++;document.getElementById('ds').textContent='Download selected ('+n+')'}
function sa(v){D.forEach(function(s,i){if(F=='ALL'||ty(s)==F)S[i]=!!v});draw()}
function dls(){var a=[];D.forEach(function(s,i){if(S[i])a.push(url(s))});if(!a.length){alert('Select items first');return}G.dl(JSON.stringify(a))}
function dla(){var a=[];D.forEach(function(s,i){if(F=='ALL'||ty(s)==F)a.push(url(s))});G.dl(JSON.stringify(a))}
fl();draw();
</script></body></html>"""

    private fun scanMedia() {
        val base = web.url ?: "https://localhost/"
        web.evaluateJavascript(mediaJs) { res ->
            val arr = JSONArray(unq(res))
            if (arr.length() == 0) { toast("No media found on this page"); return@evaluateJavascript }
            val gw = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                addJavascriptInterface(GalBridge(), "G")
            }
            gw.loadDataWithBaseURL(base, galleryHtml.replace("__DATA__", arr.toString().replace("</", "<\\/")),
                "text/html", "utf-8", null)
            galleryDlg?.dismiss()
            galleryDlg = Dialog(this, android.R.style.Theme_Material_Light_NoActionBar).apply {
                setContentView(gw); show()
                window?.setLayout(MATCH_PARENT, MATCH_PARENT)
            }
        }
    }

    // ---------- speech recognition (webkitSpeechRecognition polyfill) ----------
    private var sr: android.speech.SpeechRecognizer? = null
    private var srCont = false
    private var srStopped = false
    private var srInterim = false
    private var srLang = "en-US"

    private fun srEv(t: String, p: String = "") = runOnUiThread {
        web.evaluateJavascript("window.__wgSR&&__wgSR('$t'," + JSONObject.quote(p) + ")", null)
    }

    fun startSr(lang: String, cont: Boolean, interim: Boolean) {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 13)
            srEv("error", "not-allowed"); srEv("end"); return
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            srEv("error", "service-not-allowed"); srEv("end"); return
        }
        srCont = cont; srStopped = false; srInterim = interim; srLang = lang
        srEv("start"); listen()
    }

    fun stopSr() { srStopped = true; sr?.stopListening() }

    private fun listen() {
        sr?.destroy()
        val r = android.speech.SpeechRecognizer.createSpeechRecognizer(this); sr = r
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { srEv("audiostart") }
            override fun onBeginningOfSpeech() { srEv("speechstart") }
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { srEv("speechend") }
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onError(e: Int) {
                if (srCont && !srStopped && (e == 7 || e == 6)) { web.post { listen() }; return }
                srEv("error", when (e) { 9 -> "not-allowed"; 1, 2 -> "network"; 7, 6 -> "no-speech"; else -> "aborted" })
                srEv("end")
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (t.isNotEmpty()) srEv("result", "{\"text\":" + JSONObject.quote(t) + ",\"final\":true}")
                if (srCont && !srStopped) web.post { listen() } else srEv("end")
            }
            override fun onPartialResults(b: Bundle?) {
                if (!srInterim) return
                val t = b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (t.isNotEmpty()) srEv("result", "{\"text\":" + JSONObject.quote(t) + ",\"final\":false}")
            }
        })
        r.startListening(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, srLang)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, srInterim))
    }

    // ---------- fullscreen video + rotation ----------
    private var customView: android.view.View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var rotMode = 0
    private lateinit var rotBtn: Button

    private fun showCustom(v: android.view.View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) { cb.onCustomViewHidden(); return }
        customView = v; customCb = cb
        (window.decorView as FrameLayout).addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        applyImmersive(true)
    }

    private fun hideCustom() {
        val v = customView ?: return
        (window.decorView as FrameLayout).removeView(v); customView = null
        customCb?.onCustomViewHidden(); customCb = null
        requestedOrientation = when (rotMode) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        applyImmersive(false)
        holder.post { fixLayout() }; holder.postDelayed({ fixLayout() }, 400)
    }

    private fun rotate() {
        rotMode = (rotMode + 1) % 3
        requestedOrientation = when (rotMode) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        rotBtn.text = "⟳ " + listOf("Auto", "Land", "Port")[rotMode]
    }

    // ---------- green status chips (recording / reading) ----------
    private lateinit var statusBar: LinearLayout
    private var statusKey = ""
    private val ttsPending = java.util.concurrent.atomic.AtomicInteger(0)
    private val dismissed = mutableMapOf<String, Long>()
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusTick = object : Runnable {
        override fun run() { refreshStatus(); statusHandler.postDelayed(this, 1000) }
    }

    private fun refreshStatus() {
        if (!::statusBar.isInitialized) return
        if (::screenBtn.isInitialized) screenBtn.text = if (ScreenRecordService.active) "⏹ Stop recording" else "⏺ Screen"
        val chips = mutableListOf<Pair<String, () -> Unit>>()
        if (recorder != null) chips.add("● Recording audio" to { stopAudio() })
        if (ScreenRecordService.active) chips.add(
            (if (ScreenRecordService.kind == "audio") "● Recording device audio" else "● Recording screen") to
                { ScreenRecordService.active = false; stopService(Intent(this, ScreenRecordService::class.java)) })
        if (ttsReady && ttsPending.get() > 0 && tts.isSpeaking) chips.add("● Reading aloud" to { stopSpeech() })
        val now = System.currentTimeMillis()
        chips.removeAll { (dismissed[it.first] ?: 0L) > now }
        val key = chips.joinToString("|") { it.first }
        if (key == statusKey) return
        statusKey = key
        statusBar.removeAllViews()
        statusBar.visibility = if (chips.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        chips.forEach { (label, act) ->
            statusBar.addView(LinearLayout(this).apply {
                setBackgroundColor(0xFF2E7D32.toInt())
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(24, 4, 8, 4)
                addView(TextView(this@MainActivity).apply {
                    text = label; setTextColor(android.graphics.Color.WHITE); textSize = 14f
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(TextView(this@MainActivity).apply {
                    text = "✕"; setTextColor(android.graphics.Color.WHITE); textSize = 20f
                    setPadding(32, 8, 24, 8)
                    setOnClickListener {
                        dismissed[label] = System.currentTimeMillis() + 4000
                        act(); statusKey = ""; refreshStatus()
                    }
                })
            }, MATCH_PARENT, WRAP_CONTENT)
        }
    }

    // ---------- save bytes / blob + data downloads from pages ----------
    private var blobBuf = StringBuilder()
    private var blobName = "download"
    private var blobMime = ""

    private var outStream: OutputStream? = null
    private var outName = ""

    fun fileBegin(n: String, m: String) {
        try { outStream?.close() } catch (e: Exception) { /* ignore */ }
        outStream = null
        outName = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "download.zip" }
        try {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, outName)
                put(MediaStore.Downloads.MIME_TYPE, m.ifEmpty { "application/octet-stream" })
            }
            val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            outStream = if (u != null) contentResolver.openOutputStream(u) else null
            if (outStream == null) toast("Cannot create the file in Downloads")
        } catch (e: Exception) { toast("Cannot create file: ${e.message}") }
    }

    fun filePart(c: String) {
        try { outStream?.write(android.util.Base64.decode(c, android.util.Base64.DEFAULT)) }
        catch (e: Exception) { toast("Write failed: ${e.message}") }
    }

    fun fileEnd() {
        try { outStream?.close() } catch (e: Exception) { /* ignore */ }
        outStream = null
        toast("Saved to Downloads: $outName")
    }

    fun blobBegin(n: String, m: String) { blobBuf = StringBuilder(); blobName = n; blobMime = m }
    fun blobPart(c: String) { blobBuf.append(c) }
    fun blobEnd() {
        try {
            val bytes = android.util.Base64.decode(blobBuf.toString(), android.util.Base64.DEFAULT)
            saveBytes(blobName, blobMime, bytes)
        } catch (e: Exception) { toast("Save failed: ${e.message}") }
        blobBuf = StringBuilder()
    }

    private fun saveBytes(name0: String, mime0: String, bytes: ByteArray) {
        var name = name0.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "download" }
        val mm = MimeTypeMap.getSingleton()
        val mime = mime0.ifEmpty { mm.getMimeTypeFromExtension(name.substringAfterLast('.', "")) ?: "application/octet-stream" }
        if (!name.contains('.')) mm.getExtensionFromMimeType(mime)?.let { name += ".$it" }
        saveToDownloads(name, mime) { it.write(bytes) }
        toast("Saved to Downloads: $name")
    }

    private fun downloadSmart(u: String, cd: String?, mime: String?) {
        if (u.startsWith("blob:") || u.startsWith("data:")) {
            val name = URLUtil.guessFileName(u, cd, mime)
            web.evaluateJavascript("window.__wgSaveUrl&&__wgSaveUrl(" + JSONObject.quote(u) + "," + JSONObject.quote(name) + ")", null)
        } else downloadDM(u)
    }

    // ---------- export / import data saved by HTML pages (localStorage) ----------
    private fun exportPageData() {
        web.evaluateJavascript("JSON.stringify(Object.assign({},localStorage))") { r ->
            val j = str(r)
            if (j.isEmpty() || j == "{}") { toast("This page has no saved data"); return@evaluateJavascript }
            saveBytes("${host()}_backup_${System.currentTimeMillis() / 1000}.json", "application/json", j.toByteArray())
        }
    }

    private fun pickImport() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
        }, 6)
    }

    private var screenMode = "screen"

    private fun toggleDeviceAudio() {
        if (ScreenRecordService.active) { stopService(Intent(this, ScreenRecordService::class.java)); return }
        toast("Records the sound your phone plays (videos, music) in original quality")
        ensure(Manifest.permission.RECORD_AUDIO) { askProjection(false, "audio") }
    }

    // ---------- PDF ----------
    private fun savePdf() {
        val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(host(), web.createPrintDocumentAdapter(host()), null)
        toast("Choose 'Save as PDF' as the printer")
    }
}
