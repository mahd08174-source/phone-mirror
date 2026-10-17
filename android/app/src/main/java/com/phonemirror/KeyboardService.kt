package com.phonemirror

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A keyboard with no keys on screen: while someone is mirroring, text and key presses from the
 * viewer are delivered like a hardware keyboard plugged into the phone (no on-screen keyboard pops up).
 * When nobody is mirroring it shows a small strip with a button to switch back to your normal keyboard.
 */
class KeyboardService : InputMethodService() {
    companion object {
        @Volatile var instance: KeyboardService? = null
        @Volatile private var remote = false
        private val main = Handler(Looper.getMainLooper())

        fun setRemote(on: Boolean) {
            remote = on
            main.post { instance?.updateInputViewShown() }
        }
    }

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onEvaluateInputViewShown(): Boolean = !remote
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val d = resources.displayMetrics.density
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        }
        val tv = TextView(this).apply {
            text = "Mirror keyboard (types from your computer)"
            setTextColor(0xFF8A8A8F.toInt())
            textSize = 13f
        }
        val btn = Button(this).apply {
            text = "Switch keyboard"
            isAllCaps = false
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        }
        bar.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btn)
        return bar
    }

    /** Type text into whatever field is focused. */
    fun commit(s: String): Boolean {
        val ic = currentInputConnection ?: return false
        return ic.commitText(s, 1)
    }

    private fun press(code: Int, meta: Int): Boolean {
        val ic = currentInputConnection ?: return false
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        return true
    }

    /** Returns false when there is no focused input or the key is unknown, so the caller can fall back. */
    fun handleKey(k: String, shift: Boolean, ctrl: Boolean, ch: String): Boolean {
        val ic = currentInputConnection ?: return false
        var meta = 0
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        return when (k) {
            "enter" -> {
                val ei = currentInputEditorInfo
                val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                val noAction = ei == null ||
                    (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
                    action == EditorInfo.IME_ACTION_NONE ||
                    action == EditorInfo.IME_ACTION_UNSPECIFIED
                if (!shift && !noAction) ic.performEditorAction(action) else press(KeyEvent.KEYCODE_ENTER, meta)
                true
            }
            "bksp" -> press(KeyEvent.KEYCODE_DEL, meta)
            "del" -> press(KeyEvent.KEYCODE_FORWARD_DEL, meta)
            "left" -> press(KeyEvent.KEYCODE_DPAD_LEFT, meta)
            "right" -> press(KeyEvent.KEYCODE_DPAD_RIGHT, meta)
            "up" -> press(KeyEvent.KEYCODE_DPAD_UP, meta)
            "down" -> press(KeyEvent.KEYCODE_DPAD_DOWN, meta)
            "tab" -> press(KeyEvent.KEYCODE_TAB, meta)
            "linehome" -> press(KeyEvent.KEYCODE_MOVE_HOME, meta)
            "lineend" -> press(KeyEvent.KEYCODE_MOVE_END, meta)
            "pgup" -> press(KeyEvent.KEYCODE_PAGE_UP, meta)
            "pgdn" -> press(KeyEvent.KEYCODE_PAGE_DOWN, meta)
            "selectall" -> ic.performContextMenuAction(android.R.id.selectAll)
            "copy" -> ic.performContextMenuAction(android.R.id.copy)
            "cut" -> ic.performContextMenuAction(android.R.id.cut)
            "paste" -> ic.performContextMenuAction(android.R.id.paste)
            "letter" -> {
                if (ch.length != 1) return false
                val code = KeyEvent.keyCodeFromString("KEYCODE_" + ch.uppercase())
                if (code == KeyEvent.KEYCODE_UNKNOWN) false else press(code, meta)
            }
            else -> false
        }
    }
}
