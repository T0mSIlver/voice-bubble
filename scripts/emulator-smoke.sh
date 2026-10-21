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

# The accessibility service binds a while after the setting is written; wait for it.
for i in $(seq 30); do
  debug
  adb logcat -d -s VoiceBubble:* | grep -q "accessibility=true" && break
  [ "$i" = 15 ] && adb shell settings put secure enabled_accessibility_services "$PKG/$PKG.InsertService"
  sleep 2
done
adb logcat -d -s VoiceBubble:* | grep -q "accessibility=true" || fail "accessibility service never connected"
echo "ok: accessibility service connected"

adb shell am start -W -n "$PKG/.TestTargetActivity"
sleep 3

# Rambler mode, mic tap off: the first bubble tap opens Gboard on the invisible field; typed keys
# stand in for Rambler. The second tap must put them into the app's field, with nothing else to do.
debug --ez automic false
debug --ez record true
sleep 3
shot 2-first-tap
adb shell dumpsys window | grep -E 'mCurrentFocus' | head -1 | tee "$OUT/focus-typing.txt"
adb shell input text "rambler%stext"
sleep 2
debug
adb logcat -d -s VoiceBubble:* | grep -q "buffer='rambler text'" || fail "typed text didn't reach the buffer"
echo "ok: keys typed after the first tap land in the buffer"

debug --ez record true
sleep 4
shot 3-second-tap
adb shell uiautomator dump /sdcard/ui.xml > /dev/null
adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null
grep -q 'text="Hello rambler text"' "$OUT/ui.xml" || fail "the second tap didn't insert into the app's field"
adb shell dumpsys window | grep -E 'mInputMethodWindow|InputMethod' | grep -i 'isVisible=true\|mViewVisibility=0x0' | head -2 || true
echo "ok: the second tap inserted the text into the app's field"

# Mic tap on. This image ships Gboard; with its microphone granted (as on a phone where voice typing
# was used once), the bubble should find and tap its voice key, and on the second tap a finish key.
# Logged, not asserted: no Rambler here.
adb shell pm grant com.google.android.inputmethod.latin android.permission.RECORD_AUDIO 2>/dev/null || echo "no Gboard on this image"
debug --ez automic true --ez record true
sleep 4
shot 4-voice-typing
adb logcat -d -s VoiceBubble:* | grep "voice key" | tail -1 || echo "voice key: no attempt logged"

# Does touching the app behind keep Gboard on the field? Logged, not asserted.
read -r W H < <(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | tr x ' ')
adb shell input swipe $((W / 2)) $((H * 42 / 100)) $((W / 2)) $((H * 32 / 100)) 300
sleep 2
shot 5-after-swipe
adb logcat -d -s VoiceBubble:* | grep "ghost focus" | tail -2 || true
debug --ez record true
sleep 5
shot 6-finished
adb logcat -d -s VoiceBubble:* | grep -E "finish key|finish:" | tail -2 || true

# Probe, logged only: Gboard's floating-mode controls, for making the keyboard small while Rambler listens.
debug --ez automic false
debug --ez record true
sleep 3
debug --es tapkey "features menu"
sleep 2
shot 7-features-menu
debug --es tapkey "floating"
sleep 2
shot 8-floating
debug --es tapkey "features menu"
sleep 2
shot 9-features-again
debug --es tapkey "zzz-scan-only"
debug --es tapkey "dock|floating"
sleep 2
shot 10-docked
debug --es tapkey "zzz-scan-only"
debug --ez record true
sleep 2
adb logcat -d -s VoiceBubble:* | grep -E "tapkey|scan:" || true
debug
adb logcat -d -s VoiceBubble:* | tee "$OUT/voicebubble.log" | tail -40
adb logcat -d > "$OUT/logcat.txt"
echo "SMOKE PASS"
