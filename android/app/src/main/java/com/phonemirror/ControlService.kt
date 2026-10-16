package com.phonemirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

class ControlService : AccessibilityService() {
    companion object {
        @Volatile var instance: ControlService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private var downTs = 0.0

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val p = Point()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
            p.x to p.y
        }
    }

    fun key(k: String) {
        main.post {
            when (k) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
        }
    }

    // t = down | move | up, nx/ny normalized 0..1, ts = viewer clock in ms
    fun touch(t: String, nx: Double, ny: Double, ts: Double) {
        main.post {
            val (sw, sh) = screenSize()
            val x = (nx.coerceIn(0.0, 1.0) * sw).toFloat()
            val y = (ny.coerceIn(0.0, 1.0) * sh).toFloat()
            when (t) {
                "down" -> { xs.clear(); ys.clear(); xs.add(x); ys.add(y); downTs = ts }
                "move" -> if (xs.isNotEmpty()) { xs.add(x); ys.add(y) }
                "up" -> if (xs.isNotEmpty()) {
                    xs.add(x); ys.add(y)
                    fire((ts - downTs).toLong())
                    xs.clear(); ys.clear()
                }
            }
        }
    }

    private fun fire(elapsed: Long) {
        val x0 = xs.first(); val y0 = ys.first()
        var far = 0f
        for (i in xs.indices) far = maxOf(far, hypot(xs[i] - x0, ys[i] - y0))
        val path = Path()
        path.moveTo(x0, y0)
        val dur: Long
        if (far < 24f) {
            dur = if (elapsed > 500) elapsed.coerceAtMost(2000) else 50
        } else {
            val step = maxOf(1, xs.size / 24)
            var i = step
            while (i < xs.size) { path.lineTo(xs[i], ys[i]); i += step }
            path.lineTo(xs.last(), ys.last())
            dur = elapsed.coerceIn(80, 1500)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
            .build()
        dispatchGesture(g, null, null)
    }
}
