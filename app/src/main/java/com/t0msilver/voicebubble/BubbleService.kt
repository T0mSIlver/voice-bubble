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
 * Owns the floating bubble. One tap starts dictating, the next one stops and types the text into
 * the field focused in the app underneath. The text panel (from the notification) only shows the
 * last dictation.
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
    /** Rambler mode: the invisible field has Gboard. */
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
        buildGhost()
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
            if (ghostAdded) runCatching { wm.removeView(ghost) }
        }
        super.onDestroy()
    }

    // --- recording ---

    /** What a bubble tap does: the first starts dictating, the second stops and inserts. */
    fun toggleRecording() {
        when {
            finishing -> Unit
            typing -> finishAndInsert()
            recognizer != null -> {
                insertWhenStopped = true
                stopRecording()
            }
            settings.mode == Mode.RAMBLER -> beginTyping(tapMic = settings.autoMic)
            else -> startRecording()
        }
    }

    // --- Rambler mode ---
    // Rambler only runs inside Gboard, and Gboard only opens on a focused text field. So the first
    // tap adds an invisible one-pixel field, Gboard opens on it, and the bubble taps its mic. The
    // second tap taps Rambler's finish key, waits for the text to land, removes the field (which
    // closes Gboard) and inserts the text into the app underneath.

    private lateinit var ghost: EditText
    private var ghostAdded = false
    private var finishing = false
    private var micTapped = false
    private var floated = false

    private fun buildGhost() {
        ghost = EditText(this).apply {
            id = R.id.buffer_box
            alpha = 0f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable) { if (typing) DictationBuffer.setText(e.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
            viewTreeObserver.addOnWindowFocusChangeListener { focused -> Log.i(TAG, "ghost focus=$focused typing=$typing") }
        }
    }

    private val ghostParams by lazy {
        overlayParams(1, 1).apply {
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
    }

    private fun beginTyping(tapMic: Boolean) {
        typing = true
        DictationBuffer.clear()
        ghost.setText("")
        if (!ghostAdded) {
            wm.addView(ghost, ghostParams)
            ghostAdded = true
        }
        ghost.requestFocus()
        DictationBuffer.setRecording(true)
        DictationBuffer.setStatus(if (tapMic) "Opening Gboard's mic…" else "Keyboard open")
        ghost.postDelayed({
            if (typing) getSystemService(InputMethodManager::class.java).showSoftInput(ghost, 0)
        }, 120)
        if (tapMic) tapMicWhenKeyboardIsUp(System.currentTimeMillis())
    }

    /** Waits for the keyboard window, floats it if asked, then taps its voice key once. */
    private fun tapMicWhenKeyboardIsUp(since: Long) {
        ghost.postDelayed({
            if (!typing) return@postDelayed
            val svc = InsertService.instance
            if (svc == null) {
                toast("Tap Gboard's mic (turn on Accessibility and the bubble does it)")
                return@postDelayed
            }
            val labels = svc.keyLabels()
            when {
                labels.isNullOrEmpty() && System.currentTimeMillis() - since < 3000 -> tapMicWhenKeyboardIsUp(since)
                labels.isNullOrEmpty() -> toast("Gboard didn't open")
                settings.floatKeyboard && labels.none(InsertService.FLOATING_NOW::containsMatchIn) ->
                    toggleFloating { ok ->
                        floated = ok
                        Log.i(TAG, "float keyboard: $ok")
                        tapMic()
                    }
                else -> tapMic()
            }
        }, 250)
    }

    private fun tapMic() {
        if (!typing) return
        val result = InsertService.instance?.tapKey(InsertService.VOICE_KEY)
        if (result?.startsWith("tapped") == true) {
            micTapped = true
            DictationBuffer.setStatus("Rambler is listening")
        } else {
            toast("Couldn't find Gboard's mic; tap it yourself")
        }
        Log.i(TAG, "voice key: $result")
    }

    /** Gboard's features menu, then its "Floating mode" entry, which turns floating on or off. */
    private fun toggleFloating(done: (Boolean) -> Unit) {
        tapWhenShown(InsertService.FEATURES_MENU, System.currentTimeMillis()) { menu ->
            if (menu == null) done(false)
            else tapWhenShown(InsertService.FLOAT_TOGGLE, System.currentTimeMillis()) { done(it != null) }
        }
    }

    /** Taps the key matching [pattern] as soon as it shows; gives null after 1.5 s without it. */
    private fun tapWhenShown(pattern: Regex, since: Long, done: (String?) -> Unit) {
        bubble.postDelayed({
            val result = InsertService.instance?.tapKey(pattern)
            when {
                result?.startsWith("tapped") == true -> done(result)
                System.currentTimeMillis() - since < 1500 -> tapWhenShown(pattern, since, done)
                else -> {
                    Log.i(TAG, "no key for ${pattern.pattern}: $result")
                    done(null)
                }
            }
        }, 200)
    }

    private fun finishAndInsert() {
        finishing = true
        DictationBuffer.setStatus("Finishing…")
        val before = ghost.text.toString()
        // Rambler holds its text until its finish key; Gboard's plain voice typing has typed already.
        val svc = InsertService.instance
        var result = svc?.tapKey(InsertService.FINISH_KEY)
        // No finish key found: tapping the mic again stops listening, which may hand the text over.
        if (svc != null && micTapped && result?.startsWith("tapped") != true) result = svc.tapKey(InsertService.VOICE_KEY)
        micTapped = false
        Log.i(TAG, "finish key: $result")
        val tapped = result?.startsWith("tapped") == true
        waitForText(before, System.currentTimeMillis(), if (tapped) 4000 else 400, before, System.currentTimeMillis())
    }

    /** Waits until the field's text changed and then held still, or until the deadline. */
    private fun waitForText(before: String, start: Long, limit: Long, lastSeen: String, lastChange: Long) {
        ghost.postDelayed({
            val now = System.currentTimeMillis()
            val text = ghost.text.toString()
            val changedAt = if (text != lastSeen) now else lastChange
            val settled = text != before && now - changedAt >= 600
            if (settled || now - start >= limit) {
                Log.i(TAG, "finish: '${text.take(80)}' after ${now - start} ms")
                dockIfFloated {
                    endTyping()
                    finishing = false
                    // Let the app underneath get its focus back before typing into it.
                    bubble.postDelayed({ insertBuffer() }, 350)
                }
            } else {
                waitForText(before, start, limit, text, changedAt)
            }
        }, 150)
    }

    /** Puts Gboard back the way it was, if the first tap floated it. */
    private fun dockIfFloated(then: () -> Unit) {
        if (!floated) return then()
        floated = false
        toggleFloating { ok ->
            Log.i(TAG, "dock keyboard: $ok")
            if (!ok) toast("Couldn't put Gboard back: turn Floating off in Gboard's menu")
            then()
        }
    }

    private fun endTyping() {
        if (!typing) return
        typing = false
        DictationBuffer.setText(ghost.text.toString())
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(ghost.windowToken, 0)
        if (ghostAdded) {
            runCatching { wm.removeView(ghost) }
            ghostAdded = false
        }
        DictationBuffer.setRecording(false)
        DictationBuffer.setStatus("")
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
            if (!finishing) finishAndInsert()
            return
        }
        if (recognizer != null) {
            // The segment being spoken isn't final yet; insert once the engine hands it over.
            insertWhenStopped = true
            stopRecording()
            return
        }
        val text = DictationBuffer.snapshot().trim()
        if (text.isBlank()) {
            toast("Nothing to insert yet")
            return
        }
        lastText = text
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

    private var lastText = ""

    /** Long-press: the last dictation again, at the cursor of whatever field is focused now. */
    private fun insertLastAgain() {
        if (typing || recognizer != null) return
        if (lastText.isEmpty()) {
            toast("Nothing dictated yet")
            return
        }
        DictationBuffer.setText(lastText)
        insertBuffer()
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
            contentDescription = "Voice Bubble: tap to dictate, tap again to insert"
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
                    if (e.eventTime - e.downTime >= LONG_PRESS_MS) insertLastAgain() else toggleRecording()
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(fg)
            setHintTextColor(Color.GRAY)
            hint = "Nothing dictated yet"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            minLines = 2
            maxLines = 6
            isVerticalScrollBarEnabled = true
            background = null
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
        button("Clear") {
            DictationBuffer.clear()
            if (typing) ghost.setText("")
        }
        button("Copy") {
            val t = DictationBuffer.snapshot()
            if (t.isBlank()) toast("Nothing to copy yet") else { copyToClipboard(this, t); toast("Copied") }
        }
        button("Insert") { insertBuffer() }.apply { setTypeface(typeface, Typeface.BOLD) }

        panel.addView(statusView)
        panel.addView(box)
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
            .setContentText("Tap the bubble to dictate, tap again to insert")
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
