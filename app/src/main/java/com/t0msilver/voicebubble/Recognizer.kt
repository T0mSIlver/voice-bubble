package com.t0msilver.voicebubble

/** One speech-to-text engine. start/stop are called on the main thread. */
interface Recognizer {
    val label: String
    fun start()
    /** Ends the session gracefully; the engine still delivers its last final, then onEnded. */
    fun stop()

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onStatus(text: String)
        fun onError(reason: String)
        fun onEnded()
    }
}
