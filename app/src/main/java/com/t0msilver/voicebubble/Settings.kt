package com.t0msilver.voicebubble

import android.content.Context
import java.util.Locale

enum class Mode(val title: String, val detail: String) {
    RAMBLER(
        "Rambler (Gboard)",
        "The bubble opens a small text box and taps Gboard's mic for you, so Rambler types into it. Gboard stays on screen while it listens.",
    ),
    SCROLL(
        "Keep scrolling (Android recognizer)",
        "No keyboard on screen, so you can scroll other apps while you talk. Google's standard recognizer, not Rambler: no clean-up of ums or restarts.",
    ),
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var mode: Mode
        get() = runCatching { Mode.valueOf(prefs.getString("mode", null)!!) }.getOrDefault(Mode.RAMBLER)
        set(v) = prefs.edit().putString("mode", v.name).apply()

    /** Rambler mode: tap Gboard's mic key once the keyboard is up (needs the accessibility service). */
    var autoMic: Boolean
        get() = prefs.getBoolean("auto_mic", true)
        set(v) = prefs.edit().putBoolean("auto_mic", v).apply()

    /** Scroll mode's language, a BCP 47 tag; empty means the phone's language. */
    var languageTag: String
        get() = prefs.getString("language", "").orEmpty()
        set(v) = prefs.edit().putString("language", v).apply()

    val locale: Locale get() = languageTag.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()

    var clearAfterInsert: Boolean
        get() = prefs.getBoolean("clear_after_insert", true)
        set(v) = prefs.edit().putBoolean("clear_after_insert", v).apply()

    companion object {
        val LANGUAGES = listOf("" to "Phone language", "en-US" to "English (US)", "fr-FR" to "Français")
    }
}
