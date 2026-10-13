#!/usr/bin/env bash
# Run as one shell: emulator-runner executes each YAML script line separately.
status=0
gradle :app:connectedDebugAndroidTest --no-daemon || status=$?
if [ "$status" != 0 ]; then
  find app/build/outputs/androidTest-results -name '*.xml' -exec cat {} +
  adb logcat -d -t 2000 | rg 'AndroidRuntime|FATAL EXCEPTION|chromium|locky|Accessibility' || true
fi
exit "$status"
