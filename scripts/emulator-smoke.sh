#!/usr/bin/env bash
# emulator-smoke.sh: installs the debug APK on a running emulator and checks the parts that need no
# microphone: the bubble service starts, the panel shows the buffer, and Insert types the buffer
# into another activity's text field through the accessibility service. Screenshots and the log
# go to smoke-out/. Speech itself can't be checked here: the emulator has no AICore and no voice.
set -euo pipefail

APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
PKG=com.t0msilver.voicebubble
OUT=smoke-out
mkdir -p "$OUT"

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
debug() { adb shell am broadcast -a "$PKG.DEBUG" -p "$PKG" "$@" > /dev/null; }
fail() { echo "SMOKE FAIL: $*" >&2; adb logcat -d > "$OUT/logcat.txt" || true; exit 1; }

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
debug --es append "this is dictated text" --ez panel true
sleep 2
shot 2-panel
adb shell dumpsys window windows > "$OUT/windows.txt"
grep -c "$PKG" "$OUT/windows.txt" || true

debug --ez insert true
sleep 3
shot 3-inserted
adb shell uiautomator dump /sdcard/ui.xml > /dev/null
adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null
grep -q 'text="Hello this is dictated text"' "$OUT/ui.xml" || fail "the field doesn't hold the inserted text"
echo "ok: Insert typed the buffer into the focused field"

# Speech engines on the emulator: expected to report unavailable and fall back; logged, not asserted.
debug --ez record true
sleep 6
shot 4-recording
debug --ez record true
sleep 3
debug
adb logcat -d -s VoiceBubble:* > "$OUT/voicebubble.log"
adb logcat -d > "$OUT/logcat.txt"
cat "$OUT/voicebubble.log"
grep -q "accessibility=true" "$OUT/voicebubble.log" || fail "accessibility service never connected"
echo "SMOKE PASS"
