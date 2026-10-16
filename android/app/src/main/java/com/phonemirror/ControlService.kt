package com.phonemirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Acts like a mouse + keyboard attached to the phone.
 *  - touch: one continuous finger stroke, extended live while the mouse button is held (real drags)
 *  - wheel: scroll swipes
 *  - keys / typed text: edit the focused text field
 */
class ControlService : AccessibilityService() {
    companion object {
        @Volatile var instance: ControlService? = null
    }

    private val main = Handler(Looper.getMainLooper())

    // ---- live touch state (main thread only) ----
    private var gen = 0
    private var down = false
    private var upReq = false
    private var finishing = false
    private var inFlight = false
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var tgtX = 0f
    private var tgtY = 0f

    // ---- wheel state (main thread only) ----
    private var wheelBusy = false
    private var wAccX = 0f
    private var wAccY = 0f
    private var wX = 0f
    private var wY = 0f

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

    // ================= keys =================

    fun key(k: String) {
        main.post {
            when (k) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "enter" -> pressEnter()
                "bksp" -> deleteText(true)
                "del" -> deleteText(false)
                "left" -> moveCursor(-1)
                "right" -> moveCursor(1)
                "selectall" -> selectAll()
            }
        }
    }

    // ================= text =================

    private class Sel(val text: String, val lo: Int, val hi: Int)

    private fun focusedInput(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun readSel(n: AccessibilityNodeInfo): Sel {
        val cur = if (n.isShowingHintText) "" else (n.text?.toString() ?: "")
        var a = n.textSelectionStart
        var b = n.textSelectionEnd
        if (a < 0 || b < 0 || a > cur.length || b > cur.length) { a = cur.length; b = cur.length }
        return Sel(cur, minOf(a, b), maxOf(a, b))
    }

    private fun setSel(n: AccessibilityNodeInfo, a: Int, b: Int) {
        val args = Bundle()
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, a)
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, b)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun writeText(n: AccessibilityNodeInfo, t: String, cursor: Int) {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        setSel(n, cursor, cursor)
    }

    fun typeText(s: String) {
        main.post {
            val n = focusedInput() ?: return@post
            val sel = readSel(n)
            writeText(n, sel.text.substring(0, sel.lo) + s + sel.text.substring(sel.hi), sel.lo + s.length)
        }
    }

    private fun deleteText(backward: Boolean) {
        val n = focusedInput() ?: return
        val sel = readSel(n)
        if (sel.hi > sel.lo) {
            writeText(n, sel.text.removeRange(sel.lo, sel.hi), sel.lo)
        } else if (backward && sel.lo > 0) {
            writeText(n, sel.text.removeRange(sel.lo - 1, sel.lo), sel.lo - 1)
        } else if (!backward && sel.lo < sel.text.length) {
            writeText(n, sel.text.removeRange(sel.lo, sel.lo + 1), sel.lo)
        }
    }

    private fun moveCursor(d: Int) {
        val n = focusedInput() ?: return
        val sel = readSel(n)
        val p = if (sel.hi > sel.lo) (if (d < 0) sel.lo else sel.hi) else (sel.lo + d).coerceIn(0, sel.text.length)
        setSel(n, p, p)
    }

    private fun selectAll() {
        val n = focusedInput() ?: return
        setSel(n, 0, readSel(n).text.length)
    }

    private fun pressEnter() {
        val n = focusedInput() ?: return
        if (Build.VERSION.SDK_INT >= 30 &&
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return
        if (n.isMultiLine) {
            val sel = readSel(n)
            writeText(n, sel.text.substring(0, sel.lo) + "\n" + sel.text.substring(sel.hi), sel.lo + 1)
        }
    }

    // ================= live touch =================

    // t = down | move | up, nx/ny normalized 0..1
    fun touch(t: String, nx: Double, ny: Double, ts: Double) {
        main.post {
            if (nx.isNaN() || ny.isNaN()) return@post
            val (sw, sh) = screenSize()
            val x = (nx.coerceIn(0.0, 1.0) * sw).toFloat()
            val y = (ny.coerceIn(0.0, 1.0) * sh).toFloat()
            when (t) {
                "down" -> startTouch(x, y)
                "move" -> if (down && !upReq) { tgtX = x; tgtY = y; pump() }
                "up" -> if (down) { tgtX = x; tgtY = y; upReq = true; pump() }
            }
        }
    }

    private fun startTouch(x: Float, y: Float) {
        gen++
        down = true
        upReq = false
        finishing = false
        inFlight = false
        lastX = x; lastY = y; tgtX = x; tgtY = y
        val p = Path()
        p.moveTo(x, y)
        // willContinue = true keeps the finger "down" after this segment ends
        val s = GestureDescription.StrokeDescription(p, 0, 20, true)
        stroke = s
        send(s)
    }

    // Sends the next stroke segment toward the latest pointer position (coalesces fast moves).
    private fun pump() {
        if (!down || inFlight) return
        val s = stroke ?: return
        val moved = hypot(tgtX - lastX, tgtY - lastY) >= 1f
        if (upReq) {
            val p = Path()
            p.moveTo(lastX, lastY)
            if (moved) p.lineTo(tgtX, tgtY)
            finishing = true
            val ns = s.continueStroke(p, 0, if (moved) 30 else 20, false)
            stroke = ns
            lastX = tgtX; lastY = tgtY
            send(ns)
        } else if (moved) {
            val p = Path()
            p.moveTo(lastX, lastY)
            p.lineTo(tgtX, tgtY)
            val ns = s.continueStroke(p, 0, 16, true)
            stroke = ns
            lastX = tgtX; lastY = tgtY
            send(ns)
        }
    }

    private fun send(s: GestureDescription.StrokeDescription) {
        val g = gen
        inFlight = true
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(s).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    main.post {
                        if (g != gen) return@post
                        inFlight = false
                        if (finishing) endTouch() else pump()
                    }
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    main.post { if (g == gen) endTouch() }
                }
            },
            null
        )
        if (!ok) endTouch()
    }

    private fun endTouch() {
        down = false
        upReq = false
        finishing = false
        inFlight = false
        stroke = null
    }

    // ================= wheel =================

    // nx/ny: cursor position 0..1; ndx/ndy: wheel delta as a fraction of the displayed screen
    fun wheel(nx: Double, ny: Double, ndx: Double, ndy: Double) {
        main.post {
            if (down || nx.isNaN() || ny.isNaN()) return@post
            val (sw, sh) = screenSize()
            wX = (nx.coerceIn(0.02, 0.98) * sw).toFloat()
            wY = (ny.coerceIn(0.02, 0.98) * sh).toFloat()
            if (!ndx.isNaN()) wAccX += (ndx * sw).toFloat()
            if (!ndy.isNaN()) wAccY += (ndy * sh).toFloat()
            flushWheel()
        }
    }

    private fun flushWheel() {
        if (wheelBusy || down) return
        val dx = wAccX
        val dy = wAccY
        if (abs(dx) < 4f && abs(dy) < 4f) return
        wAccX = 0f; wAccY = 0f
        val (sw, sh) = screenSize()
        val p = Path()
        // the finger moves opposite to the wheel direction
        if (abs(dy) >= abs(dx)) {
            val d = dy.coerceIn(-0.45f * sh, 0.45f * sh)
            p.moveTo(wX, (wY + d / 2).coerceIn(8f, sh - 8f))
            p.lineTo(wX, (wY - d / 2).coerceIn(8f, sh - 8f))
        } else {
            val d = dx.coerceIn(-0.45f * sw, 0.45f * sw)
            p.moveTo((wX + d / 2).coerceIn(8f, sw - 8f), wY)
            p.lineTo((wX - d / 2).coerceIn(8f, sw - 8f), wY)
        }
        wheelBusy = true
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 90)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    main.post { wheelBusy = false; flushWheel() }
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    main.post { wheelBusy = false }
                }
            },
            null
        )
        if (!ok) wheelBusy = false
    }
}
