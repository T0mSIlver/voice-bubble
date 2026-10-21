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

/** Setup: the four permissions, the listening mode, and the switch for the bubble. */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var root: LinearLayout
    private val steps = mutableListOf<() -> Unit>()
    private lateinit var keyboardStatus: TextView
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
        para("A floating mic over any app. Tap it and Gboard's Rambler types into a small floating box instead of the app; then Insert puts the text into the field you're editing, or Copy takes it.")

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
        step("Accessibility, to tap Gboard's mic and insert into any field (optional: without it, Insert copies)", { accessibilityOn() }) {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        section("2. How the bubble listens")
        keyboardStatus = para("")
        val group = RadioGroup(this)
        Mode.entries.forEach { m ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = m.title
                isChecked = settings.mode == m
                setOnCheckedChangeListener { _, on -> if (on) settings.mode = m }
            })
            group.addView(TextView(this).apply {
                text = m.detail
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(32), 0, 0, dp(8))
            })
        }
        root.addView(group)
        root.addView(CheckBox(this).apply {
            text = "Rambler mode: tap Gboard's mic for me (needs Accessibility)"
            isChecked = settings.autoMic
            setOnCheckedChangeListener { _, on -> settings.autoMic = on }
        })
        para("Language for Keep scrolling mode:")
        val labels = Settings.LANGUAGES.map { it.second }
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(Settings.LANGUAGES.indexOfFirst { it.first == settings.languageTag }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    settings.languageTag = Settings.LANGUAGES[pos].first
                }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        })
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
        para("Tap: start or stop dictating (in Rambler mode, tap the bubble again to finish). Long-press: show or hide the text panel. Drag: move it. Turn it off from here or from its notification.")

        if (intent?.getBooleanExtra(EXTRA_START_BUBBLE, false) == true) startBubble()
    }

    override fun onResume() {
        super.onResume()
        refresh()
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

    private fun refresh() {
        steps.forEach { it() }
        val ime = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        keyboardStatus.text = if (ime.startsWith(GBOARD)) "✓ Gboard is your keyboard. Turn Rambler on in Gboard's voice typing settings."
        else "○ Rambler mode needs Gboard as your keyboard (now: ${ime.substringBefore('/').ifEmpty { "unknown" }})"
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
        val labelView = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }
        val button = Button(this).apply { text = "Allow"; setOnClickListener { fix() } }
        row.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button)
        root.addView(row)
        steps += {
            val ok = done()
            labelView.text = (if (ok) "✓ " else "○ ") + label
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
        private const val GBOARD = "com.google.android.inputmethod.latin/"
    }
}
