package com.t0msilver.voicebubble

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Owns the floating bubble and the text panel. In Rambler mode a bubble tap makes the panel's box
 * focusable, so Gboard opens on it and Rambler types into it; otherwise both windows stay
 * unfocusable, so the app underneath keeps its focused field and Tom can keep scrolling it.
 */
class BubbleService : LifecycleService() {
    private lateinit var wm: WindowManager
    private val settings by lazy { Settings(this) }

    private lateinit var bubble: FrameLayout
    private lateinit var bubbleBg: GradientDrawable
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var pulse: ObjectAnimator? = null

    private lateinit var panel: LinearLayout
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var box: EditText
    private lateinit var statusView: TextView
    private var panelShown = false

    private var recognizer: Recognizer? = null
    private var insertWhenStopped = false
    /** Rambler mode: the box has the keyboard. */
    private var typing = false
    private var running = false

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android refuses a microphone service without the permission or from the background.
            toast("Voice Bubble couldn't start: ${e.message}")
            stopSelf()
            return
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            toast("Allow Voice Bubble to display over other apps first")
            stopSelf()
            return
        }
        addBubble()
        buildPanel()
        observe()
        running = true
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_TOGGLE_PANEL -> if (running) togglePanel()
        }
        // Not sticky: a restart from the background would have no microphone access.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        recognizer?.stop()
        recognizer = null
        DictationBuffer.setRecording(false)
        if (running) {
            pulse?.cancel()
            runCatching { wm.removeView(bubble) }
            if (panelShown) runCatching { wm.removeView(panel) }
        }
        super.onDestroy()
    }

    // --- recording ---

    /** What a bubble tap does. */
    fun toggleRecording() {
        when {
            typing -> endTyping()
            recognizer != null -> stopRecording()
            settings.mode == Mode.RAMBLER -> beginTyping(tapMic = settings.autoMic)
            else -> startRecording()
        }
    }

    // --- Rambler mode ---

    private fun beginTyping(tapMic: Boolean) {
        if (!panelShown) togglePanel()
        typing = true
        panelParams.flags = panelParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        wm.updateViewLayout(panel, panelParams)
        box.setText(DictationBuffer.text.value)
        box.isFocusableInTouchMode = true
        box.requestFocus()
        box.setSelection(box.length())
        DictationBuffer.setRecording(true)
        DictationBuffer.setStatus(if (tapMic) "Opening Gboard's mic…" else "Typing into the box")
        box.postDelayed({
            if (typing) getSystemService(InputMethodManager::class.java).showSoftInput(box, 0)
        }, 120)
        if (tapMic) tapMicWhenKeyboardIsUp(System.currentTimeMillis())
    }

    /** Polls for the keyboard window, then taps its voice key once. */
    private fun tapMicWhenKeyboardIsUp(since: Long) {
        box.postDelayed({
            if (!typing) return@postDelayed
            val svc = InsertService.instance
            if (svc == null) {
                DictationBuffer.setStatus("Tap Gboard's mic (turn on Accessibility and the bubble does it)")
                return@postDelayed
            }
            val result = svc.tapVoiceKey()
            when {
                result == null && System.currentTimeMillis() - since < 3000 -> tapMicWhenKeyboardIsUp(since)
                result == null -> DictationBuffer.setStatus("The keyboard didn't open; tap the box")
                result.startsWith("tapped") -> DictationBuffer.setStatus("Rambler is listening · tap the bubble when done")
                else -> DictationBuffer.setStatus("Couldn't find Gboard's mic; tap it yourself")
            }
            Log.i(TAG, "voice key: $result")
        }, 250)
    }

    private fun endTyping() {
        if (!typing) return
        typing = false
        DictationBuffer.setText(box.text.toString())
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(box.windowToken, 0)
        box.clearFocus()
        panelParams.flags = panelParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (panelShown) wm.updateViewLayout(panel, panelParams)
        DictationBuffer.setRecording(false)
        DictationBuffer.setStatus("")
        render(DictationBuffer.text.value, "")
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Open Voice Bubble and allow the microphone")
            return
        }
        DictationBuffer.setRecording(true)
        DictationBuffer.setStatus("Starting…")
        val r = PlatformRecognizer(this, settings.locale, listener)
        recognizer = r
        r.start()
    }

    private fun stopRecording() {
        DictationBuffer.setStatus("Finishing…")
        recognizer?.stop()
    }

    private val listener = object : Recognizer.Listener {
        override fun onPartial(text: String) = DictationBuffer.setPartial(text)
        override fun onFinal(text: String) = DictationBuffer.appendFinal(text)
        override fun onStatus(text: String) = DictationBuffer.setStatus(text)
        override fun onError(reason: String) {
            DictationBuffer.setStatus(reason)
            toast(reason)
        }
        override fun onEnded() {
            recognizer = null
            DictationBuffer.promotePartial()
            DictationBuffer.setRecording(false)
            if (DictationBuffer.status.value.startsWith("Listening") || DictationBuffer.status.value == "Finishing…") {
                DictationBuffer.setStatus("Stopped")
            }
            if (insertWhenStopped) {
                insertWhenStopped = false
                insertBuffer()
            }
        }
    }

    // --- insert ---

    fun insertBuffer() {
        if (typing) {
            // Give the keyboard and the focus back to the app underneath, then type into its field.
            endTyping()
            bubble.postDelayed({ insertBuffer() }, 400)
            return
        }
        if (recognizer != null) {
            // The segment being spoken isn't final yet; insert once the engine hands it over.
            insertWhenStopped = true
            stopRecording()
            return
        }
        val text = DictationBuffer.snapshot()
        if (text.isBlank()) {
            toast("Nothing to insert yet")
            return
        }
        val svc = InsertService.instance
        if (svc != null && svc.insert(text)) {
            if (settings.clearAfterInsert) DictationBuffer.clear()
            toast("Inserted")
        } else {
            copyToClipboard(this, text)
            toast(
                if (svc == null) "Copied. Turn on Voice Bubble in Accessibility to insert directly."
                else "No text field is focused, so it's on the clipboard",
            )
        }
    }

    // --- windows ---

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    private fun addBubble() {
        val size = dp(60)
        bubbleBg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(IDLE) }
        bubble = FrameLayout(this).apply {
            background = bubbleBg
            elevation = dp(6).toFloat()
            contentDescription = "Voice Bubble: tap to dictate, long-press for the text panel"
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_mic) },
                FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        }
        bubbleParams = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(12)
            y = resources.displayMetrics.heightPixels / 2
        }
        bubble.setOnTouchListener(BubbleTouch())
        wm.addView(bubble, bubbleParams)
    }

    private inner class BubbleTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = bubbleParams.x; startY = bubbleParams.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        bubbleParams.x = startX + dx.toInt()
                        bubbleParams.y = startY + dy.toInt()
                        wm.updateViewLayout(bubble, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) {
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    if (e.eventTime - e.downTime >= LONG_PRESS_MS) togglePanel() else toggleRecording()
                    v.performClick()
                }
            }
            return true
        }
    }

    private fun buildPanel() {
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val fg = if (dark) Color.WHITE else Color.BLACK
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (dark) 0xF0202124.toInt() else 0xF8FFFFFF.toInt())
            }
        }
        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.GRAY)
        }
        box = EditText(this).apply {
            id = R.id.buffer_box
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(fg)
            setHintTextColor(Color.GRAY)
            hint = "Tap the bubble and speak. Long-press it to show or hide this panel."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            minLines = 2
            maxLines = 6
            isVerticalScrollBarEnabled = true
            background = null
            setPadding(0, dp(6), 0, dp(6))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable) { if (typing) DictationBuffer.setText(e.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
            // A tap on the box while it can't take the keyboard: open the keyboard to edit by hand.
            setOnTouchListener { _, e ->
                if (!typing && recognizer == null && e.actionMasked == MotionEvent.ACTION_UP) beginTyping(tapMic = false)
                false
            }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun button(label: String, onClick: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            setTextColor(ACCENT)
            setOnClickListener { onClick() }
            row.addView(this)
        }
        button("Hide") { togglePanel() }
        button("Clear") {
            DictationBuffer.clear()
            if (typing) box.setText("")
        }
        button("Copy") {
            val t = DictationBuffer.snapshot()
            if (t.isBlank()) toast("Nothing to copy yet") else { copyToClipboard(this, t); toast("Copied") }
        }
        button("Insert") { insertBuffer() }.apply { setTypeface(typeface, Typeface.BOLD) }

        panel.addView(statusView)
        panel.addView(box)
        // Touching the app behind can take the focus, and Gboard with it; say so rather than pretend.
        panel.viewTreeObserver.addOnWindowFocusChangeListener { focused ->
            Log.i(TAG, "panel window focus=$focused typing=$typing")
            if (!focused && typing) {
                endTyping()
                DictationBuffer.setStatus("The keyboard left when the app behind took the focus")
            }
        }
        panel.addView(row)
        panelParams = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            // At the top, so it never sits on the keyboard of the field it types into.
            gravity = Gravity.TOP
            y = dp(40)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            horizontalMargin = 0.02f
        }
    }

    fun togglePanel() {
        if (panelShown) {
            endTyping()
            wm.removeView(panel)
        } else wm.addView(panel, panelParams)
        panelShown = !panelShown
    }

    private fun observe() {
        lifecycleScope.launch {
            combine(DictationBuffer.text, DictationBuffer.partial) { t, p -> t to p }.collect { (t, p) -> render(t, p) }
        }
        lifecycleScope.launch {
            DictationBuffer.recording.collect { on ->
                bubbleBg.setColor(if (on) RECORDING else IDLE)
                pulse?.cancel()
                bubble.alpha = 1f
                if (on) pulse = ObjectAnimator.ofFloat(bubble, View.ALPHA, 1f, 0.55f).apply {
                    duration = 700
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        }
        lifecycleScope.launch { DictationBuffer.status.collect { statusView.text = it } }
    }

    private fun render(text: String, partial: String) {
        // While Gboard types into the box, the box is the source and nothing overwrites it.
        if (typing) return
        val sb = SpannableStringBuilder(text)
        if (partial.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append(' ')
            val from = sb.length
            sb.append(partial.trim())
            sb.setSpan(ForegroundColorSpan(Color.GRAY), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.ITALIC), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        box.setText(sb)
        // Keep the newest words in view.
        box.setSelection(box.length())
    }

    // --- notification ---

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bubble", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val panelToggle = PendingIntent.getService(this, 2, Intent(this, BubbleService::class.java).setAction(ACTION_TOGGLE_PANEL), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Voice Bubble is on")
            .setContentText("Tap the bubble to dictate; long-press it for the text panel")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Text panel", panelToggle).build())
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "com.t0msilver.voicebubble.STOP"
        const val ACTION_TOGGLE_PANEL = "com.t0msilver.voicebubble.TOGGLE_PANEL"
        private const val TAG = "VoiceBubble"
        private const val CHANNEL = "bubble"
        private const val NOTIFICATION_ID = 1
        private const val LONG_PRESS_MS = 450L
        private const val IDLE = 0xFF1F6F68.toInt()
        private const val RECORDING = 0xFFD93025.toInt()
        private const val ACCENT = 0xFF1F6F68.toInt()

        @Volatile var instance: BubbleService? = null
            private set

        fun start(context: Context) = context.startForegroundService(Intent(context, BubbleService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, BubbleService::class.java))
    }
}
