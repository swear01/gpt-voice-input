# Changelog

All notable changes are captured in GitHub Releases; this file summarizes them
per version. Versions are tagged and built by the release workflow; install and
update through Obtainium.

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
