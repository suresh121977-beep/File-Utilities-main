package com.example.ytsaver

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.OutputStream

object Engine {
    data class Item(val id: String, val title: String, val meta: String) {
        val thumb get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    }

    data class Spec(
        val audio: Boolean,
        val height: Int?,
        val container: String,
        val audioFmt: String,
        val start: Int?,
        val end: Int?
    )

    data class Stream(val video: String, val audio: String?, val title: String)

    data class Saved(val uri: Uri, val name: String, val mime: String)

    private fun watch(id: String) = "https://www.youtube.com/watch?v=$id"
    private val yt get() = YoutubeDL.getInstance()

    /** Extracts the 11-char video id from a YouTube link (watch, youtu.be, shorts, embed, live) */
    fun videoId(q: String): String? {
        if (!q.contains("youtu")) return null
        return Regex("(?:v=|youtu\\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})").find(q)?.groupValues?.get(1)
    }

    fun init(ctx: Context) {
        yt.init(ctx)
        FFmpeg.getInstance().init(ctx)
    }

    fun update(ctx: Context) {
        yt.updateYoutubeDL(ctx)
    }

    fun search(q: String): List<Item> {
        val r = YoutubeDLRequest("ytsearch15:$q")
        r.addOption("--flat-playlist")
        r.addOption("--print", "%(id)s\t%(title)s\t%(duration_string)s\t%(uploader)s")
        val out = yt.execute(r).out
        return out.lines().mapNotNull { line ->
            val p = line.split("\t")
            if (p.size >= 4 && p[0].length == 11) {
                val meta = listOf(p[3], p[2]).filter { it.isNotBlank() && it != "NA" }.joinToString(" · ")
                Item(p[0], p[1], meta)
            } else null
        }
    }

    /** Progressive stream URLs for the player (video + optional separate audio) */
    fun stream(id: String): Stream {
        val r = YoutubeDLRequest(watch(id))
        r.addOption("-f", "bv*[height<=720][ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*[height<=720]+ba/b")
        r.addOption("--no-playlist")
        val i = yt.getInfo(r)
        val rf = i.requestedFormats
        if (rf != null && rf.size >= 2) {
            val v = rf[0].url ?: error("No video stream")
            return Stream(v, rf[1].url, i.title ?: id)
        }
        return Stream(i.url ?: error("No playable stream found"), null, i.title ?: id)
    }

    fun download(ctx: Context, id: String, s: Spec, onProgress: (Float, String) -> Unit): String {
        val dir = File(ctx.cacheDir, "dl").apply { deleteRecursively(); mkdirs() }
        val r = YoutubeDLRequest(watch(id))
        r.addOption("--no-playlist")
        r.addOption("-o", "${dir.absolutePath}/%(title).80B [%(id)s].%(ext)s")
        if (s.audio) {
            r.addOption("-f", "bestaudio/best")
            r.addOption("-x")
            r.addOption("--audio-format", s.audioFmt)
            r.addOption("--audio-quality", "0")
        } else {
            val h = if (s.height == null) "" else "[height<=${s.height}]"
            val fmt = if (s.container == "mp4")
                "bv*[ext=mp4]$h+ba[ext=m4a]/b[ext=mp4]$h/bv*$h+ba/b$h"
            else "bv*$h+ba/b$h"
            r.addOption("-f", fmt)
            r.addOption("--merge-output-format", s.container)
        }
        if (s.start != null && s.end != null) {
            r.addOption("--download-sections", "*${s.start}-${s.end}")
            r.addOption("--force-keyframes-at-cuts")
        }
        yt.execute(r, null) { p, _, line -> onProgress(p, line) }

        val names = dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            .map { f -> save(ctx, f.name, mimeOf(f.name)) { o -> f.inputStream().use { it.copyTo(o) } } }
        dir.deleteRecursively()
        if (names.isEmpty()) error("Nothing was downloaded")
        return names.joinToString()
    }

    /** Save one frame as JPG at [sec] seconds */
    fun frame(ctx: Context, url: String, sec: Int, title: String): String {
        val m = MediaMetadataRetriever()
        try {
            m.setDataSource(url, HashMap<String, String>())
            val bmp = m.getFrameAtTime(sec * 1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: error("Could not read frame at ${fmt(sec)}")
            return save(ctx, "${title.take(60)} @${fmt(sec)}.jpg", "image/jpeg") {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
            }
        } finally {
            m.release()
        }
    }

    private fun save(ctx: Context, rawName: String, mime: String, write: (OutputStream) -> Unit): String {
        val name = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/YTSaver")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val res = ctx.contentResolver
        val uri = res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("Could not create file")
        res.openOutputStream(uri)!!.use(write)
        v.clear()
        v.put(MediaStore.MediaColumns.IS_PENDING, 0)
        res.update(uri, v, null, null)
        return name
    }

    fun listSaved(ctx: Context): List<Saved> {
        val out = mutableListOf<Saved>()
        val c = ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("Download/YTSaver%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        ) ?: return out
        c.use {
            while (it.moveToNext()) {
                val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0).toString())
                out += Saved(uri, it.getString(1) ?: "file", it.getString(2) ?: "*/*")
            }
        }
        return out
    }

    private fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "opus" -> "audio/ogg"
        "flac" -> "audio/flac"; "wav" -> "audio/wav"; "jpg" -> "image/jpeg"
        else -> "application/octet-stream"
    }

    /** "90", "1:30" or "1:02:03" -> seconds */
    fun parseTime(s: String): Int? {
        val p = s.trim().split(":").map { it.toIntOrNull() ?: return null }
        return when (p.size) {
            1 -> p[0]
            2 -> p[0] * 60 + p[1]
            3 -> p[0] * 3600 + p[1] * 60 + p[2]
            else -> null
        }
    }

    fun fmt(s: Int) =
        if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
}
