package com.example.imagestudio

import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var img: ImageView
    private lateinit var btnColor: Button
    private lateinit var btnDress: Button

    private var original: Bitmap? = null   // photo as loaded
    private var current: Bitmap? = null    // what is on screen
    private var cutout: Bitmap? = null     // subject with transparent background
    private var bgBitmap: Bitmap? = null   // optional background photo
    private var dressMode = false
    private var colorIdx = 0

    private val palette = intArrayOf(
        0xFFE11D48.toInt(), 0xFF3B82F6.toInt(), 0xFF22C55E.toInt(),
        0xFFEAB308.toInt(), 0xFF8B5CF6.toInt(), 0xFFEC4899.toInt(),
        0xFFF97316.toInt(), 0xFFFFFFFF.toInt(), 0xFF111111.toInt()
    )

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val bmp = load(it)
            original = bmp; cutout = null
            show(bmp)
        }
    }

    private val pickBg = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { bgBitmap = load(it); replaceBg() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        img = findViewById(R.id.img)
        btnColor = findViewById(R.id.btnColor)
        btnDress = findViewById(R.id.btnDress)

        findViewById<Button>(R.id.btnPick).setOnClickListener { pickPhoto.launch("image/*") }
        findViewById<Button>(R.id.btnRemove).setOnClickListener { removeBg() }
        findViewById<Button>(R.id.btnBgColor).setOnClickListener { bgBitmap = null; replaceBg() }
        findViewById<Button>(R.id.btnBgImage).setOnClickListener { pickBg.launch("image/*") }
        findViewById<Button>(R.id.btnCartoon).setOnClickListener { art(false) }
        findViewById<Button>(R.id.btnSketch).setOnClickListener { art(true) }
        findViewById<Button>(R.id.btnReset).setOnClickListener { original?.let { show(it) } }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }

        btnColor.backgroundTintList = ColorStateList.valueOf(palette[0])
        btnColor.setOnClickListener {
            colorIdx = (colorIdx + 1) % palette.size
            btnColor.backgroundTintList = ColorStateList.valueOf(palette[colorIdx])
        }
        btnDress.setOnClickListener {
            dressMode = !dressMode
            btnDress.text = if (dressMode) "Dress: tap it" else "Dress: OFF"
            if (dressMode) toast("Tap the dress or clothing in the photo")
        }
        img.setOnTouchListener { v, e ->
            if (dressMode && e.action == MotionEvent.ACTION_UP) {
                recolorAt(e.x, e.y); v.performClick()
            }
            dressMode
        }
    }

    // ------------------------------------------------------------ helpers
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun show(b: Bitmap) { current = b; img.setImageBitmap(b) }

    private fun work(label: String, block: suspend () -> Bitmap) {
        if (original == null) { toast("Pick a photo first"); return }
        lifecycleScope.launch {
            toast(label)
            try { show(block()) } catch (e: Exception) { toast("Error: ${e.message}") }
        }
    }

    private fun load(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }!!
        val orient = contentResolver.openInputStream(uri)!!.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        }
        val deg = when (orient) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        val scale = minOf(1f, 1024f / maxOf(bmp.width, bmp.height))
        val m = Matrix().apply { postRotate(deg); postScale(scale, scale) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            .copy(Bitmap.Config.ARGB_8888, true)
    }

    // ------------------------------------------------------------ background
    private suspend fun ensureCutout(): Bitmap {
        cutout?.let { return it }
        val src = original ?: error("Pick a photo first")
        val seg = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        )
        val res = seg.process(InputImage.fromBitmap(src, 0)).await()
        val fg = res.foregroundBitmap ?: error("No subject found")
        seg.close()
        cutout = fg
        return fg
    }

    private fun removeBg() = work("Removing background…") { ensureCutout() }

    private fun replaceBg() = work("Replacing background…") {
        val fg = ensureCutout()
        val bg = bgBitmap
        val color = palette[colorIdx]
        withContext(Dispatchers.Default) {
            val out = Bitmap.createBitmap(fg.width, fg.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            if (bg != null) c.drawBitmap(bg, null, Rect(0, 0, fg.width, fg.height), null)
            else c.drawColor(color)
            c.drawBitmap(fg, 0f, 0f, null)
            out
        }
    }

    // ------------------------------------------------------------ dress color
    private fun recolorAt(x: Float, y: Float) {
        val bmp = current ?: return
        val pts = floatArrayOf(x, y)
        val inv = Matrix()
        img.imageMatrix.invert(inv)
        inv.mapPoints(pts)
        val px = pts[0].toInt(); val py = pts[1].toInt()
        if (px !in 0 until bmp.width || py !in 0 until bmp.height) return
        val target = palette[colorIdx]
        work("Recoloring…") { withContext(Dispatchers.Default) { recolor(bmp, px, py, target) } }
    }

    private fun recolor(src: Bitmap, sx: Int, sy: Int, target: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val seed = FloatArray(3); Color.colorToHSV(px[sy * w + sx], seed)
        val t = FloatArray(3); Color.colorToHSV(target, t)
        val hsv = FloatArray(3)

        fun similar(c: Int): Boolean {
            if (Color.alpha(c) < 10) return false
            Color.colorToHSV(c, hsv)
            var dh = abs(hsv[0] - seed[0]); if (dh > 180) dh = 360 - dh
            val hueWeight = minOf(hsv[1], seed[1])
            return dh * hueWeight < 12 && abs(hsv[1] - seed[1]) < 0.35f && abs(hsv[2] - seed[2]) < 0.4f
        }

        val seen = BooleanArray(w * h)
        val q = IntArray(w * h)
        var head = 0; var tail = 0
        q[tail++] = sy * w + sx; seen[sy * w + sx] = true
        while (head < tail) {
            val i = q[head++]
            val c = px[i]
            Color.colorToHSV(c, hsv)
            hsv[0] = t[0]
            hsv[1] = 0.4f * hsv[1] + 0.6f * t[1]
            hsv[2] = 0.7f * hsv[2] + 0.3f * t[2]
            px[i] = Color.HSVToColor(Color.alpha(c), hsv)
            val x = i % w; val y = i / w
            if (x > 0 && !seen[i - 1] && similar(px[i - 1])) { seen[i - 1] = true; q[tail++] = i - 1 }
            if (x < w - 1 && !seen[i + 1] && similar(px[i + 1])) { seen[i + 1] = true; q[tail++] = i + 1 }
            if (y > 0 && !seen[i - w] && similar(px[i - w])) { seen[i - w] = true; q[tail++] = i - w }
            if (y < h - 1 && !seen[i + w] && similar(px[i + w])) { seen[i + w] = true; q[tail++] = i + w }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------ art filters
    private fun art(sketch: Boolean) {
        val bmp = current ?: run { toast("Pick a photo first"); return }
        work("Applying style…") { withContext(Dispatchers.Default) { artFilter(bmp, sketch) } }
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(w * h); val out = IntArray(w * h)
        val d = 2 * r + 1
        for (y in 0 until h) {
            var sum = 0
            for (x in -r..r) sum += src[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = sum / d
                sum += src[y * w + (x + r + 1).coerceAtMost(w - 1)] - src[y * w + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / d
                sum += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    private fun artFilter(bmp: Bitmap, sketch: Boolean): Bitmap {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val n = w * h
        val a = IntArray(n) { px[it] ushr 24 }
        var r = IntArray(n) { Color.red(px[it]) }
        var g = IntArray(n) { Color.green(px[it]) }
        var b = IntArray(n) { Color.blue(px[it]) }
        val out = IntArray(n)

        if (sketch) {
            val gray = IntArray(n) { (r[it] * 299 + g[it] * 587 + b[it] * 114) / 1000 }
            val blur = boxBlur(IntArray(n) { 255 - gray[it] }, w, h, 10)
            for (i in 0 until n) {
                val v = minOf(255, gray[i] * 255 / (256 - blur[i]))
                out[i] = Color.argb(a[i], v, v, v)
            }
        } else {
            r = boxBlur(r, w, h, 2); g = boxBlur(g, w, h, 2); b = boxBlur(b, w, h, 2)
            val gray = IntArray(n) { (r[it] * 299 + g[it] * 587 + b[it] * 114) / 1000 }
            fun q(v: Int) = (v / 52) * 52 + 26
            for (i in 0 until n) out[i] = Color.argb(a[i], q(r[i]), q(g[i]), q(b[i]))
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = -gray[i - w - 1] + gray[i - w + 1] - 2 * gray[i - 1] + 2 * gray[i + 1] - gray[i + w - 1] + gray[i + w + 1]
                val gy = -gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1] + gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1]
                if (abs(gx) + abs(gy) > 200) out[i] = Color.argb(a[i], 0, 0, 0)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------ save
    private fun save() {
        val bmp = current ?: run { toast("Nothing to save"); return }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "studio_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ImageStudio")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { toast("Save failed"); return }
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        toast("Saved to Pictures/ImageStudio")
    }
}
