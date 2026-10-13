#!/usr/bin/env bash
# Run as one shell: emulator-runner executes each YAML script line separately.
adb shell settings put global device_provisioned 1
adb shell settings put secure user_setup_complete 1
adb install -r fixture/build/outputs/apk/debug/fixture-debug.apk || exit 1
status=0
gradle :app:connectedDebugAndroidTest --no-daemon || status=$?
if [ "$status" != 0 ]; then
  find app/build/outputs/androidTest-results -name '*.xml' -exec cat {} +
  adb logcat -d -t 2000 | grep -E 'AndroidRuntime|FATAL EXCEPTION|chromium|locky|Accessibility' || true
fi
exit "$status"
