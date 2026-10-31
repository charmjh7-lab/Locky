# Locky

A lightweight personal Android app for intentional phone use. Android 8+; designed for Samsung phones and foldables. Package: `com.locky.app`.

## Features

- PhoneStartActivity prepared on screen-off behind the Android keyguard, visible after normal unlock; managed-app gates remain accessibility overlays. Tools and purposes remain available during free time.
- Fixed-size inline notes and direct questions on the compact start screen; separate text/check notes with a single editor scroll and pinned actions; local ChatGPT conversation list, new chats and deletion.
- Official Continue with ChatGPT: system browser, loopback callback, PKCE, state, nonce, signature/issuer/audience validation, Android Keystore encrypted credentials; account-specific models and streaming Responses API.
- Dictionary searches preserve the same start view and drafts behind popup dictionaries and restore it after full dictionary/chooser return. Remembered dictionary selection: explicit Android word processing activity, Samsung dictionary or Naver dictionary. Samsung's external search intent is not a stable API: the app may open at its home rather than prefill the word.
- Separate phone/app purpose presets, deletable defaults and persistent custom purposes, with a daily deterministic shuffle; direct input is always first. Aimless starts are counted separately.
- Per-app daily budget (default 60 min, max 120), foreground-only sessions (5–10 min), purpose reminder and extension, or a complete block during focus hours.
- A small floating countdown while a managed app is in use. Tap for session/daily remaining time, the original purpose and end use; outside touches or the close button collapse the anchored panel. Long-press and drag to move; positions persist separately for compact/wide screens and orientation, within system bars and cutout bounds. Immersive screens use smaller digits; the last minute uses a quiet amber color. The display follows the existing session and hides during the shade, overview, Locky screens, screen-off, free time and expiry. Settings → 화면 위 타이머 turns it on/off (on by default); no additional overlay permission is required. Outside the capsule/panel, touches pass to the current app without moving focus. Existing notifications/Now Bar remain independent.
- System countdown notification for the current session, with the daily budget alongside it. Android 16+ requests Live Updates using the promotion contract accepted by the device; a paused session shows a static remaining time. Tap to view daily/session remaining time. 권한·배터리 설정 shows notification/channel/promotion status and opens the relevant system settings. Samsung controls actual Now Bar placement separately, so system promotion does not prove a Now Bar chip is visible.
- Recurring focus/free schedule including overnight periods and historical setting/timezone changes; configurable advance reminders; local fact-based records with external-app totals, focus/free splits, explicit purpose links, cancellations and chronological flows. No AI briefing calls or inferred attention scores.

## First launch

Open **권한·배터리 설정**. Enable Locky's accessibility service and usage access. Allow notifications and exact alarms. For downloaded APKs, Android may require app info → menu → Allow restricted settings before accessibility can be enabled. On Samsung, exclude Locky from sleeping apps. Accessibility observes package transitions; it does not read node text, take screenshots or collect messages/passwords. Usage/notes/chat history stays on this device; only user-sent ChatGPT messages leave it. The briefing runs locally without AI calls or account access.

Daily budgets are per app and only deducted in focus hours. Daily totals reset at local midnight; an exhausted-app block stays until the next free period, including overnight focus windows. Free hours do not spend the budget. Explicitly increasing an app's allowance applies the larger budget immediately. A session pauses for notification shade, overview and the time detail screen; leaving for another app pauses that app's grant for up to five minutes. Returning within five minutes resumes the original purpose and remaining time without a new grant; managed apps keep independent paused grants. Longer absences, screen-off, explicit termination and focus/day boundaries discard the relevant grants. Daily usage never advances while the screen is off. Grants shorter than five minutes are possible when less daily time remains. Settings take effect immediately and cannot erase already recorded use.

Briefing shows the ongoing day separately from completed dates and focus-window results. Focus-end alerts open an immutable snapshot of that exact window; daily alerts default to 09:00 for yesterday, and the selected weekly day opens the seven completed days ending yesterday. Settings → 브리핑 알림 controls daily time, daily/focus alerts and the dedicated high-importance system channel. Delivery state distinguishes posted, blocked, skipped and interrupted attempts; it does not claim the device displayed a banner. Focus history begins at upgrade, never retroactively applies today's settings, and preserves raw JSON `schemaVersion: 1`, `events` and `usage` while adding `focus` and analysis metadata. Unobserved time and discrete service/scheduler observations are not proof of concentration or continuous service operation. Android recovery reserves original spans first and retains unknown intent. New usage rows retain source and rule mode as observed; historical rows keep unknown provenance/policy where necessary.

## Real platform limits

This is a voluntary self-management app, not device-owner kiosk software. Disabling accessibility, force-stopping or uninstalling it can stop enforcement. Calls, system settings, keyboard and critical system UI are not blocked. Overlays cover a selected app and prevent using it; Locky does not kill its process. Launcher/system transitions, manufacturer service suspension and multi-window behavior require real-device checks. Screen-off Activity preparation uses the system-bound accessibility service. A single recovery request at confirmed unlock handles a preparation without lifecycle acknowledgement; Android/OEM background-launch policy and visual transitions still require real-device checks. Usage access recovers gaps when available; purposes are not invented for those gaps. Notification permission/Do Not Disturb and Doze can delay alerts; exact alarm permission improves scheduled transitions.

ChatGPT integration follows the official personal/local-client OAuth documentation. It does not import conversations from ChatGPT. Connection and account quota require the user's actual approval on the device. No API key or subscription token is included in this repository. All local history is retained, and completed history is sent to maintain context; long chats can increase input usage.

## Build and release

The app version is defined by `versionName` and `versionCode` in `app/build.gradle`. Release titles read the generated APK's actual `versionName`; the PhoneStart branch remains a prerelease. The package and personal signing key stay unchanged, preserving updates and existing records, notes, chats and purposes.

JDK 21 (required by Android 16 JVM tests), Gradle 8.11.1, Android SDK 36. Java source/target compatibility and targetSdk remain 17 and 35 respectively. `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`.

GitHub Actions runs JVM policy tests, browser interaction checks, Android integration tests and APK/signature verification before publishing `Locky.apk` directly to Releases. No APK Actions artifacts are uploaded. UI comparison captures are uploaded only as artifacts of their own Actions run (`distribution/ui-*.png`, retained for 14 days). Latest **three successful `locky-build-*` releases** are kept; cleanup happens only after the new APK is verified and uploaded. Unrelated/manual releases are not touched. The fixed personal signing key is committed for reproducible personal builds; it is not a Play production signing key. Keep the key unchanged for future updates.

Sources: https://developers.openai.com/siwc/token-sharing-open-source/sign-in and https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference . Authentication helpers adapted from the user's Ailey Android project, with separate app identity/storage.

### Manual focused verification

These scripts are retained for manual browser checks and are not part of the automatic Actions test list:

- `node scripts/briefing-navigation-test.cjs`: briefing navigation and stored date/window targets.
- `node scripts/floating-timer-ui-test.cjs`: floating timer settings UI; native timer behavior is covered by the focused JVM interaction tests.
- `node scripts/start-return-ui-test.cjs`: returning to the start screen from notes and ChatGPT.

Use the repository root with Playwright and Chromium installed (`npm install --no-save --package-lock=false playwright`, `npx playwright install chromium`). Run only the script relevant to the change.
