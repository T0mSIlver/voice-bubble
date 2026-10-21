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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
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
 * Owns the floating bubble and the buffer panel. Both windows are not focusable, so the app
 * underneath keeps its focused field and Tom can keep scrolling it while he dictates.
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
    private lateinit var bufferView: TextView
    private lateinit var statusView: TextView
    private var panelShown = false

    private var recognizer: Recognizer? = null
    private var insertWhenStopped = false
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

    fun toggleRecording() {
        if (recognizer != null) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Open Voice Bubble and allow the microphone")
            return
        }
        DictationBuffer.setRecording(true)
        DictationBuffer.setStatus("Starting…")
        val r = createRecognizer(this, lifecycleScope, settings, listener)
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
        override fun onUnavailable(reason: String) = DictationBuffer.setStatus(reason)
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
        bufferView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(fg)
            maxLines = 8
            movementMethod = ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
            setPadding(0, dp(6), 0, dp(6))
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
        button("Clear") { DictationBuffer.clear() }
        button("Copy") {
            val t = DictationBuffer.snapshot()
            if (t.isBlank()) toast("Nothing to copy yet") else { copyToClipboard(this, t); toast("Copied") }
        }
        button("Insert") { insertBuffer() }.apply { setTypeface(typeface, Typeface.BOLD) }

        panel.addView(statusView)
        panel.addView(bufferView)
        panel.addView(row)
        panelParams = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            // At the top, so it never sits on the keyboard of the field it types into.
            gravity = Gravity.TOP
            y = dp(40)
            horizontalMargin = 0.02f
        }
    }

    fun togglePanel() {
        if (panelShown) wm.removeView(panel) else wm.addView(panel, panelParams)
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
        if (text.isEmpty() && partial.isEmpty()) {
            bufferView.text = "Tap the bubble and speak. Long-press it to show or hide this panel."
            bufferView.alpha = 0.5f
            return
        }
        bufferView.alpha = 1f
        val sb = SpannableStringBuilder(text)
        if (partial.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append(' ')
            val from = sb.length
            sb.append(partial.trim())
            sb.setSpan(ForegroundColorSpan(Color.GRAY), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.ITALIC), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        bufferView.text = sb
        // Keep the newest words in view.
        bufferView.post {
            val layout = bufferView.layout ?: return@post
            val scroll = layout.getLineTop(bufferView.lineCount) - bufferView.height + bufferView.paddingTop + bufferView.paddingBottom
            bufferView.scrollTo(0, scroll.coerceAtLeast(0))
        }
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
