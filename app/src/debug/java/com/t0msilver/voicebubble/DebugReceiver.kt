package com.t0msilver.voicebubble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * adb shell am broadcast -a com.t0msilver.voicebubble.DEBUG -p com.t0msilver.voicebubble --es append "text"
 * Also: --ez automic false (Rambler mode's mic tap), --ez panel true (toggle the panel), --ez insert true (tap Insert), --ez record true (tap the bubble), --es tapkey "regex" (tap a Gboard key by label), --ez floatkb false (Rambler mode's floating keyboard).
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("append")?.let(DictationBuffer::appendFinal)
        if (intent.hasExtra("automic")) Settings(context).autoMic = intent.getBooleanExtra("automic", true)
        if (intent.hasExtra("floatkb")) Settings(context).floatKeyboard = intent.getBooleanExtra("floatkb", true)
        val svc = BubbleService.instance
        if (intent.getBooleanExtra("panel", false)) svc?.togglePanel()
        if (intent.getBooleanExtra("record", false)) svc?.toggleRecording()
        if (intent.getBooleanExtra("insert", false)) svc?.insertBuffer()
        intent.getStringExtra("tapkey")?.let { p ->
            val r = InsertService.instance?.tapKey(Regex(p, RegexOption.IGNORE_CASE))
            Log.i("VoiceBubble", "tapkey $p: $r")
            Log.i("VoiceBubble", "scan: " + InsertService.lastScan.replace('\n', ';'))
        }
        Log.i(
            "VoiceBubble",
            "debug: service=${svc != null} accessibility=${InsertService.instance != null} " +
                "recording=${DictationBuffer.recording.value} status='${DictationBuffer.status.value}' buffer='${DictationBuffer.snapshot()}'",
        )
    }
}
