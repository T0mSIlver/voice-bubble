# Voice Bubble

An Android prototype: a floating mic bubble over any app that drives Gboard's Rambler. Tap it,
ramble while you keep using the phone, tap it again, and the text goes into the field you were
typing in.

Rambler has no API: it runs only inside Gboard, from Gboard's mic key. So Voice Bubble drives it.
The first tap focuses an invisible one-pixel text field, Gboard opens on it, and the accessibility
service taps Gboard's mic key. The second tap presses Rambler's finish key, waits for the text,
closes the keyboard and pastes the text into the field of the app underneath.

The catch: while Rambler listens, Gboard is on screen. Switch Gboard to its floating mode
(Gboard toolbar → ⋮ → Floating) and shrink it into a corner; the rest of the screen stays usable.

There is also a **Keep scrolling** mode with no keyboard at all: Google's standard Android
recognizer instead of Rambler (no clean-up of ums and restarts).

## Install

1. On the phone, open the repo's **Releases** page, download `voice-bubble-debug.apk` from the
   latest build, and open it. Allow your browser to install unknown apps when Android asks.
2. Android 15+ blocks "display over other apps" and Accessibility for apps installed from a
   browser. Unblock both once: Settings → Accessibility → tap the greyed **Voice Bubble** entry
   and dismiss the "Restricted setting" dialog; then Settings → Apps → Voice Bubble → ⋮ →
   **Allow restricted settings**. The ⋮ entry only appears after that blocked attempt.
3. Open Voice Bubble and allow, in order: microphone, notifications, display over other apps,
   and Accessibility (it taps Gboard's mic and inserts into other apps; without it, you tap
   Gboard's mic yourself and Insert copies instead).
4. **Turn the bubble on**.

## Use

1. Tap the text field you want to fill, so it has the cursor.
2. **Tap** the bubble: Gboard opens and Rambler starts listening. Scroll, read, switch apps.
3. **Tap** it again: the text goes into that field.

- **Long-press**: insert the last dictation again.
- **Drag**: move the bubble.
- Turn it off from the app or from its notification.

If the second tap doesn't insert, the bubble didn't recognise Rambler's finish key. Open Voice
Bubble → "If the second tap doesn't insert" → **Refresh and copy**, and send the list of key
labels it shows.

## Build

`./gradlew assembleDebug` (JDK 21, Android SDK 37). CI builds the APK, runs
`scripts/emulator-smoke.sh` on an API 36 emulator, and publishes each `main` build as a release.
The debug key is in the repo on purpose so every build installs over the last.

## What's verified

CI runs `scripts/emulator-smoke.sh` on an API 36 Google APIs emulator, which ships Gboard (but not
Rambler, and has no voice):

- The bubble service starts; a tap opens Gboard on the invisible field; keys typed then land in
  the bubble's buffer, not the app behind.
- The second tap, with nothing else done, puts that text into another activity's field ("Hello"
  became "Hello rambler text") through the accessibility service's paste.
- With Gboard's microphone allowed, the bubble finds Gboard's "Use voice typing" key.
- A swipe on the app behind left the focus on the invisible field.

Not verified anywhere: Rambler itself, the label of its finish key (the bubble matches done,
finish, stop, insert and their French forms), and whether Rambler keeps listening while you scroll a
real app. That needs a Pixel with Rambler.
