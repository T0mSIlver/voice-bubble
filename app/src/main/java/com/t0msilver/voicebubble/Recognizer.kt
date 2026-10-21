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
        /** The engine can't run on this phone; the chain tries the next one. Called before any audio. */
        fun onUnavailable(reason: String)
        fun onError(reason: String)
        fun onEnded()
    }
}

/** Tries each engine in order until one runs. */
class ChainRecognizer(
    private val factories: List<(Recognizer.Listener) -> Recognizer>,
    private val outer: Recognizer.Listener,
) : Recognizer {
    private var current: Recognizer? = null
    private var stopped = false
    override val label get() = current?.label ?: "none"

    override fun start() = startAt(0)

    private fun startAt(i: Int) {
        if (i >= factories.size || stopped) {
            outer.onError("No speech engine available on this phone")
            outer.onEnded()
            return
        }
        var skipped = false
        val r = factories[i](object : Recognizer.Listener by outer {
            override fun onUnavailable(reason: String) {
                skipped = true
                outer.onStatus("$reason, trying the next engine")
                startAt(i + 1)
            }

            // An engine that bowed out may still report its end; the session isn't over.
            override fun onEnded() {
                if (!skipped) outer.onEnded()
            }
        })
        current = r
        r.start()
    }

    override fun stop() {
        stopped = true
        current?.stop()
    }
}
