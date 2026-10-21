#!/usr/bin/env bash
# emulator-smoke.sh: installs the debug APK on a running emulator and checks what needs no Gboard
# and no voice: the bubble service starts, a bubble tap opens the floating box with the keyboard on
# it, keys typed then land in the buffer, and Insert types the buffer into another activity's field
# through the accessibility service. It also logs whether a swipe on the app behind takes the
# keyboard away from the box. Screenshots and logs go to smoke-out/.
set -euo pipefail

APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
PKG=com.t0msilver.voicebubble
OUT=smoke-out
mkdir -p "$OUT"

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
debug() { adb shell am broadcast -a "$PKG.DEBUG" -p "$PKG" "$@" > /dev/null; }
fail() {
  echo "SMOKE FAIL: $*" >&2
  adb logcat -d > "$OUT/logcat.txt" || true
  echo "--- VoiceBubble log"; adb logcat -d -s VoiceBubble:* || true
  echo "--- text fields on screen"; grep -o 'text="[^"]*"[^>]*class="android.widget.EditText"' "$OUT/ui.xml" 2>/dev/null || true
  echo "--- focus"; adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' || true
  exit 1
}

adb install -r -g "$APK"
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
adb shell settings put secure enabled_accessibility_services "$PKG/$PKG.InsertService"
adb shell settings put secure accessibility_enabled 1
adb logcat -c

adb shell am start -W -n "$PKG/.MainActivity" --ez start_bubble true
sleep 4
shot 1-setup
adb shell dumpsys activity services "$PKG" | grep -q "BubbleService" || fail "BubbleService is not running"
echo "ok: bubble service running"

adb shell am start -W -n "$PKG/.TestTargetActivity"
sleep 3

# Rambler mode, mic tap off: a bubble tap opens the floating box with the keyboard on it. Typed
# keys stand in for Rambler; they must land in the box, not the app behind.
debug --ez automic false --ez panel true
sleep 1
debug --ez record true
sleep 3
shot 2-box-with-keyboard
adb shell dumpsys window | grep -E 'InputMethod.*(isVisible|mViewVisibility)|mInputMethodWindow' | head -3 || true
adb shell dumpsys window | grep -E 'mCurrentFocus' | head -2 | tee "$OUT/focus-typing.txt"
adb shell input text "rambler%stext"
sleep 2
debug
adb logcat -d -s VoiceBubble:* | grep -q "buffer='rambler text'" || fail "typed text didn't reach the box's buffer"
echo "ok: keys typed while the box is open land in the buffer"

debug --ez insert true
sleep 3
shot 3-inserted
adb shell uiautomator dump /sdcard/ui.xml > /dev/null
adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null
grep -q 'text="Hello rambler text"' "$OUT/ui.xml" || fail "the app's field doesn't hold the inserted text"
echo "ok: Insert typed the buffer into the app's focused field"

# Mic tap on. This image ships Gboard; with its microphone granted (as on a phone where voice typing
# was used once), the bubble should find and tap its voice key. Logged, not asserted: no Rambler here.
adb shell pm grant com.google.android.inputmethod.latin android.permission.RECORD_AUDIO 2>/dev/null || echo "no Gboard on this image"
debug --ez automic true --ez record true
sleep 4
shot 4-voice-typing
adb logcat -d -s VoiceBubble:* | grep "voice key" | tail -1

# The open question: does touching the app behind keep the keyboard on the box? Logged, not asserted.
read -r W H < <(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | tr x ' ')
adb shell input swipe $((W / 2)) $((H * 42 / 100)) $((W / 2)) $((H * 32 / 100)) 300
sleep 2
shot 5-after-swipe
adb logcat -d -s VoiceBubble:* | grep "panel window focus" | tail -2
adb shell dumpsys window | grep -E 'mCurrentFocus' | head -1 | tee "$OUT/focus-after-swipe.txt"
debug
adb logcat -d -s VoiceBubble:* > "$OUT/voicebubble.log"
adb logcat -d > "$OUT/logcat.txt"
cat "$OUT/voicebubble.log"
grep -q "accessibility=true" "$OUT/voicebubble.log" || fail "accessibility service never connected"
echo "SMOKE PASS"
