# Live timer / Now Bar

## Failure and fix

The previous timer only appended `android.requestPromotedOngoing` after building a plain notification. The key is valid, but it does not implement the original Android 16 promotion contract. In `android16-release`, `Notification.hasPromotableCharacteristics()` requires `isColorizedRequested()` for ordinary ongoing notifications. In `android16-qpr2-release`, the `uiRichOngoing` flag selects either that original contract or the newer contract requiring a promotion request and forbidding colorization. Therefore SDK_INT 36 alone cannot distinguish these implementations.

`Notices.timerNotification` builds the modern, non-colorized request first and asks the device's public predicate. Only on SDK 36, if that candidate fails, it builds the original colorized form and uses it only if the device accepts it. It never writes FLAG_PROMOTED_ONGOING itself. Standard notification style, the existing LOW channel, private visibility, notification ID, notification tap destination, and system chronometer are retained. No media-session impersonation, private Samsung metadata, new foreground service, or extra runtime permission is needed.

The same path builds the paused notification, with a static remaining time and no chronometer. This avoids dropping the promotion request when opening the notification shade or Locky's time detail. Screen-off still ends the grant and cancels the notification, as the existing product contract requires; usage charging and unlock behavior are unchanged. This change does not run a timer or charge usage on the lock screen.

## Permission and presentation

The setup screen reads app notification permission, timer channel importance, the device's eligibility predicate, `canPostPromotedNotifications()`, and the actual posted notification's FLAG_PROMOTED_ONGOING separately. Eligibility does not imply permission; permission does not imply system promotion; system promotion does not prove Samsung displayed a Now Bar or status chip. It opens the app's promotion settings when available, otherwise the existing timer channel/app settings. Existing channel choices are preserved.

Community evidence is mixed: TADA reports a successful integration; a separate developer's Samsung Remote Test Lab tests show that One UI 8.5 can grant promotion while withholding the chip/Now Bar. Do not promise Samsung presentation based on the standard API result alone. No Samsung physical-device verification was performed for this change.

## Focused verification

Use JDK 21, Gradle 8.11.1, SDK 36, and build-tools 35.0.0. `gradle :app:testDebugUnitTest --tests com.locky.app.NoticesTest :app:lintDebug` compiles/tests without packaging an APK. Robolectric runs against Android 15 and 16 and checks promotion eligibility, pause/resume, cancellation, denial, channel preservation, and settings fallback. `node scripts/nowbar-ui-test.cjs` checks the setup renderer's supported/denied/blocked/idle/ordinary/promoted states.

On a Samsung test device, allow notifications and start a managed session. Check the ticking notification and chip, open/close the shade, open the time-detail screen and return, and end the session. `adb shell dumpsys notification` can corroborate the request, colorization, and system-granted promotion flag. Visually check the Now Bar separately. Screen-off should cancel the session under the current Locky rules; it is not a valid test of an active lock-screen countdown.

## Verification result 2026-10-14

All 16 notification tests passed on the Android 15/16 JVM runtimes, including a regression test that reproduces the previous notification's rejection on original Android 16. The setup renderer checks and JavaScript syntax checks passed. Android lint completed with zero errors and 10 existing warnings outside the changed notification code. APK packaging and device/emulator tests were not run.

## Sources checked 2026-10-14

- Android framework, original contract: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/app/Notification.java
- Android framework, feature-flagged transition: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/core/java/android/app/Notification.java
- Current Android requirements: https://developer.android.com/develop/ui/views/notifications/live-update
- TADA implementation: https://blog-tech.tadatada.com/articles/2026-07-13-android-live-update-notification-samsung-now-bar
- Developer's Samsung test results: https://www.freecodecamp.org/news/react-native-live-activities-handbook/
- Samsung developer forum: https://forum.developer.samsung.com/t/now-bar/38681 (redirects to a migrated page; its old thread body was not retrievable, so it was not used to establish API behavior).
