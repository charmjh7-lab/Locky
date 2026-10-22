#!/usr/bin/env bash
set -euo pipefail
mkdir -p distribution
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb install -r fixture/build/outputs/apk/debug/fixture-debug.apk
adb shell settings put system screen_off_timeout 600000
adb logcat -c
adb shell am instrument -w -r -e class com.locky.app.GateIdleTest com.locky.app.test/androidx.test.runner.AndroidJUnitRunner | tee distribution/device-test.txt
adb logcat -d -s LockyIdleTest:I LockyGate:D AndroidRuntime:E > distribution/device-log.txt
grep -q 'OK (1 test)' distribution/device-test.txt
