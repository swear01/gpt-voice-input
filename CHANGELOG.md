# Changelog

All notable changes are captured in GitHub Releases; this file summarizes them
per version. Versions are tagged and built by the release workflow; install and
update through Obtainium.

## v0.1.7

- **Fix quiet recordings**: the capture source now prefers
  `VOICE_RECOGNITION` (platform-tuned for speech recognition, AGC on most
  devices) with raw `UNPROCESSED` as fallback. Raw mic signals have no gain
  and were too quiet for gpt-transcribe — observed on-device as a near-zero
  meter and failed recognition. No effects are attached to the session.
- **Privacy-safe level diagnostics**: each submission logs the chosen source
  and the recorded WAV's peak/RMS in dBFS (numbers only, never audio
  content) so quiet-input problems can be confirmed from logcat.

## v0.1.6

- **Mic level meter rework (WebRTC-style)**: the meter now follows the
  industry-standard WebRTC `AudioLevel` algorithm — peak amplitude with
  hold-and-decay (emitted ~8×/s, hold divided by 4) instead of RMS, mapped
  from dBFS over a -40 dB floor so quiet/normal/loud speech spreads across the
  meter. UI changed to a segmented 7-bar meter that lights progressively with
  a smooth bobbing animation (per-frame lerp, no layout churn), still ~30 fps
  and analysis-side only.

## v0.1.5

- **Auto-stop persistence (#7)**: auto-stop is now stored as exact integer
  milliseconds (0 = OFF, 1000–3000 in 200 ms steps) with safe one-time
  migration from the legacy Float preference; the slider no longer reopens as
  OFF, corrupt values fall back to the 1.8 s default, and opening Settings
  never mutates a valid value.
- **Live mic level meter (#6)**: while listening, a compact horizontal meter
  shows the analysis-side input level (RMS → dBFS → 0..1, fast attack / slow
  release, ~30 fps throttled, no layout churn). It resets/hides outside
  LISTENING and never touches the upload path.
- **Localization (#5)**: UI follows the system/app locale — English remains the
  complete default, Traditional Chinese (`values-b+zh+Hant`) is added, Android
  13+ per-app language is supported via `localeConfig` (default System), and
  all user-facing Kotlin strings were externalized into resources.
- **Recognizer result delivery (#4)**: RecognitionActivity uses standard
  launch mode (singleTask removed), honors the documented
  `EXTRA_RESULTS_PENDINGINTENT` forwarding route (with
  `EXTRA_RESULTS_PENDINGINTENT_BUNDLE` merging), keeps the classic
  `RESULT_OK + EXTRA_RESULTS` path, adds privacy-safe delivery diagnostics, and
  guards duplicate sessions without a task-mode hack.

## v0.1.4

- Package front door: `Android Settings → Apps → GPT Voice Input → Open` now
  opens Settings via an exported `activity-alias` (`ACTION_MAIN +
  CATEGORY_INFO`). No `CATEGORY_LAUNCHER` was added — the app still has no
  app-drawer icon.
- `SettingsActivity` remains `android:exported="false"`; external access goes
  through the narrow alias.
- PackageManager regression tests (front door resolves, no launcher entry,
  `ACTION_RECOGNIZE_SPEECH` unchanged).

## v0.1.3

- Settings reorganized: top-level **OpenAI / Transcription / Recording /
  Profile & backup**; the Advanced section and the Effective Configuration
  dump were removed.
- **Transcription**: Languages / Context / Keywords are now directly editable
  and are exactly what is sent to `gpt-transcribe`; saving creates a runtime
  profile override atomically (validation failure → nothing stored).
- Keywords unified into a single source; legacy `customTerms` are merged on
  load and migrated away on save/import/reset.
- Profile & backup moved to top-level action rows: Import / Export / Export
  full backup / Reset transcription profile (reset keeps API key and auto-stop).
- CI: fixed the previous-release versionCode gate (now resolves the newest
  stable release via the Releases API, fails when none exists) and added a
  structural generic-config APK assertion.
- Repository made fully generic: no owner-specific vocabulary anywhere
  (source, tests, docs, samples).

## v0.1.2

- Recognition UI is now a bottom-anchored, keyboard-height translucent panel;
  the calling app stays visible. No IME / Accessibility / overlay permission.
- Settings UI polish: edge-to-edge insets, single page title, key-status row,
  Advanced chevron affordance.
- Placeholder API keys (`REPLACE_WITH_YOUR_OPENAI_API_KEY`) are rejected on
  import; 401/403 show safe app-owned messages with Cancel + Open Settings
  (no key fragments, no developer URLs).

## v0.1.1

- First generic public release with runtime-import architecture: the public
  APK contains only neutral defaults; personal configuration is imported at
  runtime and persisted app-privately (survives updates).
- Versioned settings import/export (`schemaVersion: 1`) with safe export
  (no API key) and explicit full backup (plaintext key, warned).
- Fixed the Settings inflate crash (missing layout dimensions on styled
  TextViews) and restored custom-terms state.
- Runtime imported profile layer: default asset → imported profile → custom
  terms; API key stored in the Android Keystore.

## v0.1.0

- Initial release: SwiftKey mic → `ACTION_RECOGNIZE_SPEECH` → recording →
  OpenAI `gpt-transcribe` → `EXTRA_RESULTS` → text inserted.
- Raw-audio split pipeline (no denoise/AGC on the upload path; analysis copy
  only for VAD/endpoint detection), auto-stop 1.0–3.0 s / OFF, 8 s no-speech
  timeout, 120 s cap.
- No launcher entry; settings via the gear button in the recognition panel.
- Signed release pipeline (GitHub Actions) with Obtainium-friendly assets.
