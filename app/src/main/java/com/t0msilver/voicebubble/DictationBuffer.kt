package com.t0msilver.voicebubble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** The text dictated so far, shared by the bubble, the panel and the insert path. */
object DictationBuffer {
    private val _text = MutableStateFlow("")
    private val _partial = MutableStateFlow("")
    private val _recording = MutableStateFlow(false)
    private val _status = MutableStateFlow("")

    /** Finalized text. */
    val text: StateFlow<String> = _text
    /** The recognizer's current guess for the segment being spoken; replaced, never appended. */
    val partial: StateFlow<String> = _partial
    val recording: StateFlow<Boolean> = _recording
    /** One short line about the engine: which one runs, a download, an error. */
    val status: StateFlow<String> = _status

    /** Rambler mode: Gboard edits the box, and the box's text is the buffer. */
    fun setText(s: String) { _text.value = s }
    fun setPartial(s: String) { _partial.value = s }
    fun setRecording(on: Boolean) { _recording.value = on }
    fun setStatus(s: String) { _status.value = s }

    fun appendFinal(s: String) {
        val piece = s.trim()
        _partial.value = ""
        if (piece.isEmpty()) return
        _text.update { joinSegments(it, piece) }
    }

    /** On stop, a guess no final replaced is kept rather than lost (localvoxtral does the same). */
    fun promotePartial() = appendFinal(_partial.value)

    /** What Insert and Copy take: finals plus the live guess. */
    fun snapshot(): String = joinSegments(_text.value, _partial.value.trim())

    fun clear() {
        _text.value = ""
        _partial.value = ""
    }
}

internal fun joinSegments(a: String, b: String): String = when {
    b.isEmpty() -> a
    a.isEmpty() -> b
    a.last().isWhitespace() -> a + b
    else -> "$a $b"
}
