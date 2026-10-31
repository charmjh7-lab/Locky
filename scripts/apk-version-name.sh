#!/usr/bin/env bash
set -euo pipefail
badging=$("${ANDROID_HOME:?}/build-tools/35.0.0/aapt" dump badging "${1:?APK path required}")
version_name=$(sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" <<<"$badging")
if [ -z "$version_name" ]; then
  echo 'APK versionName is missing' >&2
  exit 1
fi
printf '%s\n' "$version_name"
