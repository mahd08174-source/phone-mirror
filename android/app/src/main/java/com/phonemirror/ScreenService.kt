package com.phonemirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ScreenService : Service() {
    companion object {
        const val ACTION_STOP = "com.phonemirror.STOP"
        const val MAX_INFLIGHT = 2      // frames sent but not yet acknowledged by the viewer
        const val MAX_SIDE = 1024       // long edge in pixels
        const val JPEG_QUALITY = 50
        @Volatile var running = false
        @Volatile var code = ""
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    @Volatile private var viewerConnected = false
    @Volatile private var stopped = false
    @Volatile private var inflight = 0
    @Volatile private var lastAck = 0L
    private var lastSent = 0L
    private var w = 0
    private var h = 0
    private var frameBmp: Bitmap? = null
    private val jpeg = ByteArrayOutputStream(256 * 1024)

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
        code = c
        running = true

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        thread = HandlerThread("capture").also { it.start() }
        val handler = Handler(thread!!.looper)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopAll() }
        }, handler)

        setupCapture(handler)
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

    private fun setupCapture(handler: Handler) {
        val dm = resources.displayMetrics
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(dm.widthPixels, dm.heightPixels))
        // width multiple of 16 so the row stride has no padding on most devices (skips a per-frame crop copy)
        w = ((dm.widthPixels * scale).toInt() / 16) * 16
        h = (dm.heightPixels * scale).toInt() and 1.inv()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader = r
        display = projection!!.createVirtualDisplay(
            "mirror", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                val sock = ws
                if (!viewerConnected || sock == null || now - lastSent < 16) return@setOnImageAvailableListener
                // Flow control: never get more than MAX_INFLIGHT frames ahead of the viewer.
                // If acks stop for 1.5s, reset so a lost ack can't freeze the stream.
                if (inflight >= MAX_INFLIGHT) {
                    if (now - lastAck > 1500) inflight = 0 else return@setOnImageAvailableListener
                }
                if (sock.queueSize() > 150_000) return@setOnImageAvailableListener
                lastSent = now
                val p = img.planes[0]
                val bw = p.rowStride / p.pixelStride
                var bmp = frameBmp
                if (bmp == null || bmp.width != bw || bmp.height != h) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888)
                    frameBmp = bmp
                }
                p.buffer.rewind()
                bmp!!.copyPixelsFromBuffer(p.buffer)
                val toSend = if (bw == w) bmp else Bitmap.createBitmap(bmp, 0, 0, w, h)
                jpeg.reset()
                toSend.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
                if (toSend !== bmp) toSend.recycle()
                if (inflight == 0) lastAck = now
                inflight++
                sock.send(jpeg.toByteArray().toByteString())
            } finally {
                img.close()
            }
        }, handler)
    }

    private fun connect(url: String, c: String) {
        val req = Request.Builder().url("$url/?role=host&code=$c").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = try { JSONObject(text) } catch (e: Exception) { return }
                when (o.optString("type")) {
                    "viewer-joined" -> {
                        inflight = 0
                        lastAck = SystemClock.elapsedRealtime()
                        viewerConnected = true
                        webSocket.send(JSONObject().put("type", "meta").put("w", w).put("h", h).toString())
                    }
                    "viewer-left" -> stopAll()
                    else -> {
                        val t = o.optString("t")
                        if (t == "ack") {
                            if (inflight > 0) inflight--
                            lastAck = SystemClock.elapsedRealtime()
                            return
                        }
                        val ctl = ControlService.instance ?: return
                        if (t == "key") ctl.key(o.optString("k"))
                        else if (t == "down" || t == "move" || t == "up") {
                            ctl.touch(t, o.optDouble("x"), o.optDouble("y"), o.optDouble("ts"))
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
        try { reader?.close() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        frameBmp?.recycle(); frameBmp = null
        thread?.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }
}
