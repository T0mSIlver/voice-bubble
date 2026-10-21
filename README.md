# Voice Bubble

An Android prototype: a floating mic bubble over any app. Tap it to dictate while you keep
scrolling; the text collects in a panel you show or hide, and Insert types it into the field you
are editing in whatever app is open (or copies it).

Gboard's Rambler only runs inside the keyboard, which takes half the screen. Google doesn't expose
Rambler to other apps. The closest model a third-party app can call is ML Kit's GenAI Speech
Recognition in advanced mode: the on-device Gemini Nano speech model, on Pixel 10 and 11 through
AICore. Voice Bubble uses that first, then ML Kit's basic mode, then Android's own recognizer.

## Install

1. On the phone, open the repo's **Releases** page, download `voice-bubble-debug.apk` from the
   latest build, and open it. Allow your browser to install unknown apps when Android asks.
2. Open Voice Bubble and allow, in order: microphone, notifications, display over other apps.
3. For Insert to type into other apps, turn on **Voice Bubble** under Settings → Accessibility →
   Downloaded apps. Android 13+ blocks this for sideloaded apps at first: if the switch is greyed
   out, open Settings → Apps → Voice Bubble → ⋮ → **Allow restricted settings**, then try again.
   Without it, Insert copies to the clipboard instead.
4. Tap **Check engines** to see which speech engines your phone has, then **Turn the bubble on**.

## Use

- **Tap** the bubble: start or stop dictating. It turns red and pulses while it listens.
- **Long-press**: show or hide the text panel (live words in grey until they're final).
- **Drag**: move it.
- **Insert**: puts the text at the cursor of the focused field in the app underneath, then clears
  the panel (a setting). Tap the field first so it has the cursor. **Copy** and **Clear** do
  what they say.
- Turn it off from the app or from its notification.

## Build

`./gradlew assembleDebug` (JDK 21, Android SDK 37). CI builds the APK, runs
`scripts/emulator-smoke.sh` on an API 36 emulator (bubble service, panel, Insert into another
activity's field through the accessibility service), and publishes each `main` build as a release.
The debug key is in the repo on purpose so every build installs over the last.

## Not done

- Speech is only checked on a real phone; the emulator has neither AICore nor a voice.
- Rambler's clean-up (dropping "um", fixing restarts) isn't there. ML Kit's Prompt API runs Gemini
  Nano on the same phones and could do it as a pass over the buffer before Insert.
- A Voxtral Realtime backend (the self-hosted vLLM server, over the VPN) would make it work the
  same on any phone.
