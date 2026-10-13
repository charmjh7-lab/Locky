# Locky

A lightweight personal Android app for intentional phone use. Android 8+; designed for Samsung phones and foldables. Package: `com.locky.app`.

## Features

- Accessibility overlay prepared on screen-off, visible after unlock; tools and purposes remain available during free time.
- Fixed-size inline notes and direct questions on the compact start screen; separate text/check notes with a single editor scroll and pinned actions; local ChatGPT conversation list, new chats and deletion.
- Official Continue with ChatGPT: system browser, loopback callback, PKCE, state, nonce, signature/issuer/audience validation, Android Keystore encrypted credentials; account-specific models and streaming Responses API.
- Remembered dictionary selection: explicit Android word processing activity, Samsung dictionary or Naver dictionary. Samsung's external search intent is not a stable API: the app may open at its home rather than prefill the word.
- Separate phone/app purpose presets, deletable defaults and persistent custom purposes, with a daily deterministic shuffle; direct input is always first. Aimless starts are counted separately.
- Per-app daily budget (default 60 min, max 120), foreground-only sessions (5–10 min), purpose reminder and extension, or a complete block during focus hours.
- System countdown notification for the daily budget. Tap to view daily/session remaining time. Android's status bar presentation differs by One UI version; a third-party app cannot guarantee Samsung Clock's exact timer chip.
- Recurring focus/free schedule including overnight focus periods; configurable 30/20/10/5/1-minute reminders and custom offsets; daily and weekly local narrative briefings with all observed apps, timestamps and purpose-linked detours.

## First launch

Open **권한·배터리 설정**. Enable Locky's accessibility service and usage access. Allow notifications and exact alarms. For downloaded APKs, Android may require app info → menu → Allow restricted settings before accessibility can be enabled. On Samsung, exclude Locky from sleeping apps. Accessibility observes package transitions; it does not read node text, take screenshots or collect messages/passwords. Usage/notes/chat history stays on this device; only user-sent ChatGPT messages leave it. The briefing runs locally without AI calls or account access.

Daily budgets are per app and only deducted in focus hours. Daily totals reset at local midnight; an exhausted-app block stays until the next free period, including overnight focus windows. Free hours do not spend the budget. Explicitly increasing an app's allowance applies the larger budget immediately. A session pauses for notification shade, overview and the time detail screen; leaving for another app or turning the screen off ends the current session, and the next entry asks again. Daily usage never advances while the screen is off. Grants shorter than five minutes are possible when less daily time remains. Settings take effect immediately and cannot erase already recorded use.

## Real platform limits

This is a voluntary self-management app, not device-owner kiosk software. Disabling accessibility, force-stopping or uninstalling it can stop enforcement. Calls, system settings, keyboard and critical system UI are not blocked. Overlays cover a selected app and prevent using it; Locky does not kill its process. Launcher/system transitions, manufacturer service suspension and multi-window behavior require real-device checks. Screen-off preloading minimizes a flash after unlock, but Android/OEM scheduling cannot guarantee zero frames of the underlying app. Usage access recovers gaps when available; purposes are not invented for those gaps. Notification permission/Do Not Disturb and Doze can delay alerts; exact alarm permission improves scheduled transitions.

ChatGPT integration follows the official personal/local-client OAuth documentation. It does not import conversations from ChatGPT. Connection and account quota require the user's actual approval on the device. No API key or subscription token is included in this repository. All local history is retained, and completed history is sent to maintain context; long chats can increase input usage.

## Build and release

Version 1.1.0 (`versionCode 2`) upgrades the original signed installation without deleting records, notes, chats or purposes.

JDK 17, Gradle 8.11.1, Android SDK 35. `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`.

GitHub Actions runs JVM policy tests, browser interaction checks, Android integration tests and APK/signature verification before publishing `Locky.apk` directly to Releases. No APK Actions artifacts are uploaded. Latest **three successful `locky-build-*` releases** are kept; cleanup happens only after the new APK is verified and uploaded. Unrelated/manual releases are not touched. The fixed personal signing key is committed for reproducible personal builds; it is not a Play production signing key. Keep the key unchanged for future updates.

Sources: https://developers.openai.com/siwc/token-sharing-open-source/sign-in and https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference . Authentication helpers adapted from the user's Ailey Android project, with separate app identity/storage.
