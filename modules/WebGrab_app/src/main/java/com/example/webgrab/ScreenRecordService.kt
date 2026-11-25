package com.example.webgrab

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScreenRecordService : Service() {
    companion object {
        @Volatile var active = false
        @Volatile var kind = ""
    }

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var file: File? = null
    private var audioRec: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var capturing = false

    override fun onBind(i: Intent?): IBinder? = null

    private fun toast(s: String) = Handler(Looper.getMainLooper()).post {
        Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null) { stopSelf(); return START_NOT_STICKY }
        val mode = i.getStringExtra("mode") ?: "screen"
        kind = mode
        val mic = mode == "screen" && i.getBooleanExtra("mic", false) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec", "Recording", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "rec")
            .setContentTitle(if (mode == "audio") "WebGrab is recording device audio" else "WebGrab is recording the screen")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(1, n, type)
        try { begin(i, mic, mode) } catch (e: Exception) { toast("Recording failed: ${e.message}"); stopSelf() }
        return START_NOT_STICKY
    }

    private fun begin(i: Intent, mic: Boolean, mode: String) {
        val code = i.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = i.getParcelableExtra<Intent>("data") ?: return
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data); projection = p
        p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, Handler(Looper.getMainLooper()))
        if (mode == "audio") { beginDeviceAudio(p); active = true; return }

        val dm = resources.displayMetrics
        val w = dm.widthPixels and 1.inv(); val h = dm.heightPixels and 1.inv()
        val f = File(cacheDir, "screen_${System.currentTimeMillis()}.mp4"); file = f
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        if (mic) r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (mic) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100); r.setAudioEncodingBitRate(192000); r.setAudioChannels(1)
        }
        r.setVideoSize(w, h); r.setVideoFrameRate(30); r.setVideoEncodingBitRate(6_000_000)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        display = p.createVirtualDisplay("webgrab", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        r.start(); recorder = r; active = true
    }

    // Captures the sound the phone plays (original quality, lossless WAV)
    private fun beginDeviceAudio(p: MediaProjection) {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val min = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(min * 4)
            .setAudioPlaybackCaptureConfig(cfg).build()
        val f = File(cacheDir, "device_${System.currentTimeMillis()}.wav"); file = f
        audioRec = r; capturing = true; r.startRecording()
        audioThread = Thread {
            val out = RandomAccessFile(f, "rw")
            out.write(ByteArray(44))
            val buf = ByteArray(min * 2); var total = 0L
            while (capturing) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) { out.write(buf, 0, n); total += n }
            }
            out.seek(0); out.write(wavHeader(total)); out.close()
        }.also { it.start() }
    }

    private fun wavHeader(total: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + total).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(2)
        b.putInt(44100); b.putInt(44100 * 2 * 2); b.putShort(4); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(total.toInt())
        return b.array()
    }

    override fun onDestroy() {
        active = false
        capturing = false
        try { audioThread?.join(3000) } catch (e: Exception) {}
        try { audioRec?.stop() } catch (e: Exception) {}
        try { audioRec?.release() } catch (e: Exception) {}
        val r = recorder; recorder = null
        try { r?.stop() } catch (e: Exception) {}
        try { r?.release() } catch (e: Exception) {}
        try { display?.release() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        val f = file
        if (f != null && f.exists() && f.length() > 44) {
            try {
                val isAudio = f.name.endsWith(".wav")
                val v = ContentValues()
                val uri = if (isAudio) {
                    v.put(MediaStore.Audio.Media.DISPLAY_NAME, f.name)
                    v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    v.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/WebGrab")
                    contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v)
                } else {
                    v.put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                    v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WebGrab")
                    contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                }
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
                    toast("Saved: " + (if (isAudio) "Music/WebGrab/" else "Movies/WebGrab/") + f.name)
                }
            } catch (e: Exception) { toast("Save failed: ${e.message}") }
            f.delete()
        }
        super.onDestroy()
    }
}
