package com.phonemirror

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.security.SecureRandom

class MainActivity : Activity() {
    private val REQ = 42
    private val GREY = 0xFF8A8A8F.toInt()
    private lateinit var codeView: TextView
    private lateinit var statusView: TextView
    private lateinit var urlInput: EditText
    private lateinit var button: Button
    private lateinit var ctlButton: Button
    private lateinit var kbButton: Button
    private lateinit var prefs: android.content.SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private var wasRunning = false
    private val tick = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, 1000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        prefs = getSharedPreferences("p", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        root.addView(label("Mirror", 34f, Color.WHITE, true))
        root.addView(label("Show this phone on your computer", 15f, GREY).apply { setPadding(0, dp(8), 0, dp(28)) })

        codeView = label("", 52f, Color.WHITE, true)
        root.addView(codeView)
        statusView = label("", 15f, GREY).apply { setPadding(0, dp(4), 0, dp(20)) }
        root.addView(statusView)

        urlInput = EditText(this).apply {
            hint = "wss://your-relay.up.railway.app"
            setHintTextColor(0xFF55555C.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setText(prefs.getString("url", ""))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            background = rounded(0xFF0C0C0E.toInt(), 16, 0xFF26262A.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        root.addView(urlInput, full().also { it.bottomMargin = dp(14) })

        button = Button(this).apply {
            text = "Generate code"
            isAllCaps = false
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            stateListAnimator = null
            background = rounded(Color.WHITE, 16)
        }
        root.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))

        ctlButton = Button(this).apply {
            text = "Enable touch control"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            stateListAnimator = null
            background = rounded(0xFF0C0C0E.toInt(), 16, 0xFF26262A.toInt())
        }
        root.addView(ctlButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).also { it.topMargin = dp(12) })
        kbButton = Button(this).apply {
            text = "Enable keyboard"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            stateListAnimator = null
            background = rounded(0xFF0C0C0E.toInt(), 16, 0xFF26262A.toInt())
        }
        root.addView(kbButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).also { it.topMargin = dp(12) })
        setContentView(root)

        kbButton.setOnClickListener {
            if (!kbEnabled()) startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            else (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }

        ctlButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        button.setOnClickListener {
            if (ScreenService.running) {
                startService(Intent(this, ScreenService::class.java).setAction(ScreenService.ACTION_STOP))
                return@setOnClickListener
            }
            val url = urlInput.text.toString().trim().trimEnd('/')
            if (!url.startsWith("wss://") && !url.startsWith("ws://")) {
                statusView.text = "Enter the relay address (wss://...)"
                return@setOnClickListener
            }
            prefs.edit().putString("url", url).apply()
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        if (resultCode != RESULT_OK || data == null) {
            statusView.text = "Screen capture was not allowed."
            return
        }
        val code = (SecureRandom().nextInt(900000) + 100000).toString()
        val i = Intent(this, ScreenService::class.java)
            .putExtra("code", code)
            .putExtra("url", prefs.getString("url", ""))
            .putExtra("resultCode", resultCode)
            .putExtra("data", data)
        startForegroundService(i)
    }

    private fun kbEnabled(): Boolean =
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .enabledInputMethodList.any { it.packageName == packageName }

    private fun kbSelected(): Boolean =
        (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "").startsWith("$packageName/")

    private fun render() {
        kbButton.text = when {
            !kbEnabled() -> "Enable keyboard"
            !kbSelected() -> "Select Mirror keyboard"
            else -> "Keyboard: on"
        }
        ctlButton.text = if (ControlService.instance != null) "Touch control: on" else "Enable touch control"
        if (ScreenService.running) {
            val c = ScreenService.code
            codeView.text = if (c.length == 6) c.substring(0, 3) + " " + c.substring(3) else ""
            statusView.text = "Enter this code on your computer"
            button.text = "Stop sharing"
            urlInput.isEnabled = false
            wasRunning = true
        } else {
            codeView.text = ""
            if (wasRunning) { statusView.text = ""; wasRunning = false }
            button.text = "Generate code"
            urlInput.isEnabled = true
        }
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
}
