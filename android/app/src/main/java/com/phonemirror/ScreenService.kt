package com.phonemirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Screen capture -> hardware H.264 (MediaCodec) -> WebSocket relay.
 * The VirtualDisplay renders straight into the encoder's input surface, so there is no
 * per-frame bitmap copy or JPEG work on the CPU.
 *
 * Wire format (binary): byte 0 = 1 for a keyframe (SPS/PPS prepended), else 0; rest = Annex B NAL units.
 */
class ScreenService : Service() {
    companion object {
        const val ACTION_STOP = "com.phonemirror.STOP"
        const val MAX_SIDE = 1280          // long edge in pixels
        const val FPS = 60
        const val BITRATE = 8_000_000      // bits per second
        const val MAX_QUEUE = 300_000      // bytes queued on the socket before frames are dropped
        @Volatile var running = false
        @Volatile var code = ""
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private var encThread: Thread? = null
    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    @Volatile private var viewerConnected = false
    @Volatile private var stopped = false
    @Volatile private var waitingKey = true
    @Volatile private var config: ByteArray? = null
    private var w = 0
    private var h = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) { stopAll(); return START_NOT_STICKY }
        val c = intent.getStringExtra("code") ?: return START_NOT_STICKY
        val url = intent.getStringExtra("url") ?: return START_NOT_STICKY
        val resultCode = intent.getIntExtra("resultCode", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        }

        goForeground()
        if (data == null) { stopAll(); return START_NOT_STICKY }

        stopped = false
        waitingKey = true
        viewerConnected = false
        config = null
        code = c
        running = true

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        thread = HandlerThread("capture").also { it.start() }
        val handler = Handler(thread!!.looper)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopAll() }
        }, handler)

        try {
            setupCapture()
        } catch (e: Exception) {
            stopAll()
            return START_NOT_STICKY
        }
        connect(url, c)
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("share", "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScreenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "share")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Sharing your screen")
            .setContentText("Tap Stop to end")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun createEncoder(width: Int, height: Int): MediaCodec {
        var last: Exception? = null
        for (full in listOf(true, false)) {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                f.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                // keep frames flowing on a static screen so a requested keyframe always comes out
                f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                if (full) {
                    f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    f.setInteger(MediaFormat.KEY_PRIORITY, 0)
                    if (Build.VERSION.SDK_INT >= 29) f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return c
            } catch (e: Exception) {
                last = e
                try { c.release() } catch (_: Exception) {}
            }
        }
        throw last ?: IllegalStateException("no encoder")
    }

    private fun setupCapture() {
        val dm = resources.displayMetrics
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(dm.widthPixels, dm.heightPixels))
        // encoders like dimensions that are multiples of 16
        w = maxOf(16, ((dm.widthPixels * scale).toInt() + 8) / 16 * 16)
        h = maxOf(16, ((dm.heightPixels * scale).toInt() + 8) / 16 * 16)

        val c = createEncoder(w, h)
        codec = c
        val surface = c.createInputSurface()
        c.start()
        display = projection!!.createVirtualDisplay(
            "mirror", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
        encThread = Thread({ drainLoop(c) }, "enc-out").also { it.start() }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (!stopped) {
                val i = c.dequeueOutputBuffer(info, 20_000)
                if (i < 0) continue
                try {
                    val buf = c.getOutputBuffer(i)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            config = data
                        } else {
                            sendFrame(data, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        }
                    }
                } finally {
                    c.releaseOutputBuffer(i, false)
                }
            }
        } catch (e: Exception) {
            if (!stopped) stopAll()
        }
    }

    private fun sendFrame(data: ByteArray, key: Boolean) {
        val sock = ws
        if (!viewerConnected || sock == null) return
        if (sock.queueSize() > MAX_QUEUE) {
            // never queue lag: drop, and make sure the next thing sent is a keyframe
            if (!waitingKey) { waitingKey = true; requestKey() }
            return
        }
        if (waitingKey && !key) return
        if (key) waitingKey = false
        val cfg = config
        val out: ByteArray
        if (key && cfg != null) {
            out = ByteArray(1 + cfg.size + data.size)
            System.arraycopy(cfg, 0, out, 1, cfg.size)
            System.arraycopy(data, 0, out, 1 + cfg.size, data.size)
        } else {
            out = ByteArray(1 + data.size)
            System.arraycopy(data, 0, out, 1, data.size)
        }
        out[0] = if (key) 1 else 0
        sock.send(out.toByteString())
    }

    private fun requestKey() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {}
    }

    private fun connect(url: String, c: String) {
        val req = Request.Builder().url("$url/?role=host&code=$c").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = try { JSONObject(text) } catch (e: Exception) { return }
                when (o.optString("type")) {
                    "viewer-joined" -> {
                        waitingKey = true
                        viewerConnected = true
                        webSocket.send(JSONObject().put("type", "meta").put("w", w).put("h", h).toString())
                        requestKey()
                    }
                    "viewer-left" -> stopAll()
                    else -> {
                        val t = o.optString("t")
                        if (t == "ack") return
                        if (t == "need-key") { waitingKey = true; requestKey(); return }
                        val ctl = ControlService.instance ?: return
                        if (t == "key") ctl.key(o.optString("k"))
                        else if (t == "down" || t == "move" || t == "up") {
                            ctl.touch(t, o.optDouble("x"), o.optDouble("y"), o.optDouble("ts"))
                        } else if (t == "wheel") {
                            ctl.wheel(o.optDouble("x"), o.optDouble("y"), o.optDouble("dx"), o.optDouble("dy"))
                        } else if (t == "type") {
                            ctl.typeText(o.optString("s"))
                        }
                    }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { stopAll() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { stopAll() }
        })
    }

    private fun stopAll() {
        if (stopped) return
        stopped = true
        running = false
        code = ""
        viewerConnected = false
        try { ws?.close(1000, null) } catch (e: Exception) {}
        try { display?.release() } catch (e: Exception) {}
        try { codec?.stop() } catch (e: Exception) {}
        try { codec?.release() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        thread?.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }
}
