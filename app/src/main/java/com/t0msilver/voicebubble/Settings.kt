package com.t0msilver.voicebubble

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import java.util.Locale

enum class Engine(val title: String) {
    AUTO("Auto: Gemini Nano, then basic, then Android"),
    MLKIT_ADVANCED("Gemini Nano only (ML Kit advanced)"),
    MLKIT_BASIC("ML Kit basic"),
    PLATFORM("Android recognizer"),
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var engine: Engine
        get() = runCatching { Engine.valueOf(prefs.getString("engine", null)!!) }.getOrDefault(Engine.AUTO)
        set(v) = prefs.edit().putString("engine", v.name).apply()

    /** A BCP 47 tag; empty means the phone's language. */
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

fun createRecognizer(context: Context, scope: CoroutineScope, settings: Settings, listener: Recognizer.Listener): Recognizer {
    val locale = settings.locale
    val advanced = { l: Recognizer.Listener -> MlKitRecognizer(scope, locale, true, l) }
    val basic = { l: Recognizer.Listener -> MlKitRecognizer(scope, locale, false, l) }
    val platform = { l: Recognizer.Listener -> PlatformRecognizer(context, locale, l) }
    val chain = when (settings.engine) {
        Engine.AUTO -> listOf(advanced, basic, platform)
        Engine.MLKIT_ADVANCED -> listOf(advanced)
        Engine.MLKIT_BASIC -> listOf(basic)
        Engine.PLATFORM -> listOf(platform)
    }
    return ChainRecognizer(chain, listener)
}
