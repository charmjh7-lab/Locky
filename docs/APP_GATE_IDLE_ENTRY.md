# APP_GATE idle entry correction — 2026-10-22

Scope: existing WebView, one accessibility window, current design and policy/input validation. No native screen conversion; PhoneStartActivity is unchanged.

## Evidence

- Baseline: `ceab0b2`, `refactor/app-gate-render-lifecycle-20261020`.
- Before `58c922b` (2026-10-14), GuardService exposed the retained root and navigated it without a Chromium visual-state callback barrier. `58c922b` introduced that barrier. Later readiness generations, invisibility and recovery paths accumulated around it. This identifies changes in the critical path, not the exact onset of the user's Samsung symptom.
- Baseline `applyExposure()` sets the WebView INVISIBLE in HIDDEN, WAITING and SUSPENDED. Keeping `renderingActive=true` and calling `onResume()` does not override WebView visibility.
- Chromium `AwContents.onVisibilityChanged()` forwards the view's visibility to `setViewVisibilityInternal()`, and `updateWebContentsVisibility()` forwards HIDDEN/VISIBLE to WebContents. This is separate from `onResume()`.
  Source: https://chromium.googlesource.com/chromium/src/+/refs/heads/main/android_webview/java/src/org/chromium/android_webview/AwContents.java
- Baseline resets every new entry, even when JavaScript acknowledges exactly the same DOM revision. It also requests viewport layout on every entry transition.

## Change

- Keep the WebView and hierarchy VISIBLE. Start the existing window at alpha 0 before attachment; expose it at alpha 1 only for a prepared current entry or the existing recovery actions. No opacity animation or partially opaque intermediate state.
- Preserve NOT_TOUCHABLE/NOT_FOCUSABLE in concealment; WAITING intercepts touches and the native transition/navigation fences still reject stale actions. Alpha is never used as an input authorization signal.
- Cache only the revision actually confirmed by a visual-state callback. A new entry must acknowledge its own navigation before reusing unchanged pixels. Any intervening revision requires a new visual-state callback. Detachment, document/error recovery, size changes and actual window visibility loss invalidate reuse.
- Observe DOM mutations, including disabled controls, sheets, theme and viewport attributes, so manually uncounted changes cannot reuse old pixels. Polling remains off while concealed; there is no keepalive timer, animation or wake lock.
- An unchanged entry no longer schedules viewport measurement solely because its entry token changed.

## Verification boundary

The focused pipeline runs the existing APP_GATE browser flow, GateRestoreTest and OverlayRenderingTest, and one real Android 15 WebView test: conceal, remain idle for 70 seconds, verify `document.visibilityState` stays visible and input stays blocked, then re-enter using the same WebView. It records launch-to-interactive time separately from compositor display time.

These checks do not establish Samsung SM-F966N / Android 17 compositor latency or hours-long OEM process survival. The implementation removes confirmed visibility/lifecycle and redundant-frame barriers; the user's actual first visible frame still needs observation on that device.
