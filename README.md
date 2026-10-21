# Voice Bubble

An Android prototype: a floating mic bubble over any app that gets Gboard's Rambler to type into a
small floating text box instead of the app you're in. From the box, Insert puts the text into the
field you're editing in that app, or Copy takes it.

Rambler has no API: it runs only inside Gboard, from Gboard's mic key. So Voice Bubble doesn't
replace it, it drives it. A bubble tap focuses the floating box, Gboard opens on it, and the
accessibility service taps Gboard's mic key for you. Tap the bubble again to finish.

The catch: while Rambler listens, Gboard is on screen, as it is everywhere. Gboard's floating
keyboard mode (in Gboard's toolbar) makes it small. Whether you can scroll the app behind while
Rambler keeps listening is the open question; see "What's verified".

For dictating while you scroll, there is a second mode, **Keep scrolling**: no keyboard, Google's
standard Android recognizer instead of Rambler (no clean-up of ums and restarts).

## Install

1. On the phone, open the repo's **Releases** page, download `voice-bubble-debug.apk` from the
   latest build, and open it. Allow your browser to install unknown apps when Android asks.
2. Open Voice Bubble and allow, in order: microphone, notifications, display over other apps.
3. Turn on **Voice Bubble** under Settings → Accessibility → Downloaded apps. It taps Gboard's mic
   and inserts into other apps. Android 13+ blocks this for sideloaded apps at first: if the
   switch is greyed out, open Settings → Apps → Voice Bubble → ⋮ → **Allow restricted settings**,
   then try again. Without it, you tap Gboard's mic yourself and Insert copies instead.
4. **Turn the bubble on**.

## Use

- **Tap** the bubble: the box opens and Rambler starts listening. Tap again to finish.
- **Long-press**: show or hide the box.
- **Drag**: move the bubble.
- **Insert**: puts the box's text at the cursor of the focused field in the app underneath, then
  clears the box (a setting). Tap that field first so it has the cursor.
- Tap the box to edit the text by hand with the keyboard.
- Turn it off from the app or from its notification.

## Build

`./gradlew assembleDebug` (JDK 21, Android SDK 37). CI builds the APK, runs
`scripts/emulator-smoke.sh` on an API 36 emulator, and publishes each `main` build as a release.
The debug key is in the repo on purpose so every build installs over the last.

## What's verified

On the emulator (no Gboard there, so typed keys stand in for Rambler): the bubble service starts,
a bubble tap opens the box with the keyboard on it, keys land in the box, and Insert types the text
into another app's field. The smoke log also records what a swipe on the app behind does to the
keyboard. Not verified anywhere: Gboard's mic key being found and tapped, and Rambler itself; they
need a Pixel with Rambler.
