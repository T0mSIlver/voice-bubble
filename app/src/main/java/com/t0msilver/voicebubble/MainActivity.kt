package com.t0msilver.voicebubble

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.AdapterView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Setup: the four permissions, the engine, the language, and the switch for the bubble. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settings: Settings
    private lateinit var root: LinearLayout
    private val steps = mutableListOf<() -> Unit>()
    private lateinit var engineStatus: TextView
    private lateinit var bubbleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root); fitsSystemWindows = true })

        heading("Voice Bubble")
        para("A floating mic over any app. Tap it to dictate while you keep scrolling; the text collects in a panel (long-press the bubble) that you insert into the field you're editing, or copy.")

        section("1. Permissions")
        step("Microphone", { has(Manifest.permission.RECORD_AUDIO) }) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        step("Notifications (for the bubble's off switch)", { has(Manifest.permission.POST_NOTIFICATIONS) }) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        step("Display over other apps", { AndroidSettings.canDrawOverlays(this) }) {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        step("Accessibility, to insert into any field (optional: without it, Insert copies)", { accessibilityOn() }) {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        section("2. Speech engine")
        val group = RadioGroup(this)
        Engine.entries.forEach { e ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = e.title
                isChecked = settings.engine == e
                setOnCheckedChangeListener { _, on -> if (on) settings.engine = e }
            })
        }
        root.addView(group)

        val labels = Settings.LANGUAGES.map { it.second }
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(Settings.LANGUAGES.indexOfFirst { it.first == settings.languageTag }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (settings.languageTag != Settings.LANGUAGES[pos].first) {
                        settings.languageTag = Settings.LANGUAGES[pos].first
                        checkEngines()
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        })
        engineStatus = para("")
        root.addView(Button(this).apply { text = "Check engines"; setOnClickListener { checkEngines() } })
        root.addView(CheckBox(this).apply {
            text = "Clear the text after Insert"
            isChecked = settings.clearAfterInsert
            setOnCheckedChangeListener { _, on -> settings.clearAfterInsert = on }
        })

        section("3. Bubble")
        bubbleButton = Button(this).apply {
            setOnClickListener {
                if (BubbleService.instance != null) BubbleService.stop(this@MainActivity) else startBubble()
                root.postDelayed({ refresh() }, 300)
            }
        }
        root.addView(bubbleButton)
        para("Tap: start or stop dictating. Long-press: show or hide the text panel. Drag: move it. Turn it off from here or from its notification.")

        checkEngines()
        if (intent?.getBooleanExtra(EXTRA_START_BUBBLE, false) == true) startBubble()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun startBubble() {
        when {
            !has(Manifest.permission.RECORD_AUDIO) -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            !AndroidSettings.canDrawOverlays(this) ->
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            // Started while this screen is visible: Android only lets a microphone service start from the foreground.
            else -> BubbleService.start(this)
        }
    }

    private fun checkEngines() {
        engineStatus.text = "Checking engines…"
        scope.launch {
            val locale = settings.locale
            val adv = MlKitRecognizer.describeStatus(locale, advanced = true)
            val basic = MlKitRecognizer.describeStatus(locale, advanced = false)
            val platform = when {
                android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this@MainActivity) -> "on-device available"
                android.speech.SpeechRecognizer.isRecognitionAvailable(this@MainActivity) -> "available (may use the network)"
                else -> "not installed"
            }
            engineStatus.text = "For ${locale.toLanguageTag()}:\n• Gemini Nano (ML Kit advanced): $adv\n• ML Kit basic: $basic\n• Android recognizer: $platform"
        }
    }

    private fun refresh() {
        steps.forEach { it() }
        bubbleButton.text = if (BubbleService.instance != null) "Turn the bubble off" else "Turn the bubble on"
    }

    private fun accessibilityOn(): Boolean {
        val enabled = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(this, InsertService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun step(label: String, done: () -> Boolean, fix: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val text = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }
        val button = Button(this).apply { text = "Allow"; setOnClickListener { fix() } }
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button)
        root.addView(row)
        steps += {
            val ok = done()
            text.text = (if (ok) "✓ " else "○ ") + label
            button.visibility = if (ok) View.GONE else View.VISIBLE
        }
    }

    private fun heading(s: String) = root.addView(TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        setTypeface(typeface, Typeface.BOLD)
    })

    private fun section(s: String) = root.addView(TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    })

    private fun para(s: String) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(4), 0, dp(4))
        root.addView(this)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_START_BUBBLE = "start_bubble"
    }
}
