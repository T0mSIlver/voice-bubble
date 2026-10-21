package com.t0msilver.voicebubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Types the buffer into the focused text field of whatever app is on screen. */
class InsertService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /** True when the text went into a field. The clipboard holds the text either way. */
    fun insert(text: String): Boolean {
        val node = focusedEditable()
        Log.i(TAG, "insert: field=${node?.let { "${it.packageName}/${it.viewIdResourceName} '${it.text}' sel=${it.textSelectionStart}" }}")
        if (node == null) return false
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val start = node.textSelectionStart.takeIf { it in 0..current.length } ?: current.length
        val end = node.textSelectionEnd.takeIf { it in start..current.length } ?: start
        // A space before the dictation when it lands right after a word.
        val piece = if (start > 0 && !current[start - 1].isWhitespace()) " $text" else text

        copyToClipboard(this, piece)
        node.refresh()
        val before = node.text?.toString()
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            node.refresh()
            Log.i(TAG, "insert: paste accepted, field now '${node.text}'")
            // Some fields accept the action and paste nothing; fall through to set-text then.
            if (node.text?.toString() != before) return true
        }

        // Fields that refuse paste: rewrite the whole text, then put the cursor after the insert.
        val updated = current.substring(0, start) + piece + current.substring(end)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        val set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(TAG, "insert: set-text ${if (set) "accepted" else "refused"}")
        if (!set) return false
        val cursor = start + piece.length
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
        return true
    }

    /**
     * Taps a key on the keyboard (Gboard exposes its keys to accessibility for TalkBack), found by
     * its label. Returns what it did, for the log, or null when there's no keyboard window yet.
     * The labels it saw are kept in [lastScan] so the setup screen can show them.
     */
    fun tapKey(pattern: Regex): String? {
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        val root = ime.root ?: return "keyboard window has no nodes"
        val seen = mutableListOf<String>()
        val queue = ArrayDeque(listOf(root))
        var tapped: String? = null
        while (queue.isNotEmpty() && tapped == null) {
            val n = queue.removeFirst()
            val label = listOfNotNull(n.contentDescription, n.text, n.viewIdResourceName).joinToString(" | ")
            if (label.isNotBlank()) seen += label
            if (pattern.containsMatchIn(label)) {
                var target: AccessibilityNodeInfo? = n
                while (target != null && !target.isClickable) target = target.parent
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) tapped = label
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        lastScan = "pattern ${pattern.pattern}: ${tapped?.let { "tapped '$it'" } ?: "no match"}\n" + seen.joinToString("\n")
        return tapped?.let { "tapped '$it'" } ?: "no key among ${seen.size} labels: ${seen.take(40).joinToString("; ")}"
    }

    /** The field Tom was editing in the app underneath; never Voice Bubble's own box. */
    private fun focusedEditable(): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable && it.viewIdResourceName != BOX_ID }?.let { return it }
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { it.isEditable && it.viewIdResourceName != BOX_ID }?.let { return it }
        }
        return null
    }

    companion object {
        private const val TAG = "VoiceBubble"
        private const val BOX_ID = "com.t0msilver.voicebubble:id/buffer_box"
        val VOICE_KEY = Regex("voice|micro|dictat|rambler|vocal|speak|parler", RegexOption.IGNORE_CASE)
        /** Rambler's finish key; the label isn't documented, so English and French guesses. */
        val FINISH_KEY = Regex("\\b(done|finish|finished|stop|insert|terminé|terminer|arrêter|insérer|valider)\\b", RegexOption.IGNORE_CASE)

        /** The labels the last key search saw, for the setup screen's diagnostics. */
        @Volatile var lastScan: String = ""
            private set

        @Volatile var instance: InsertService? = null
            private set
    }
}

fun copyToClipboard(context: android.content.Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("Voice Bubble", text))
}
