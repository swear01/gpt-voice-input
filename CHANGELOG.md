# Changelog

All notable changes are captured in GitHub Releases; this file summarizes them
per version. Versions are tagged and built by the release workflow; install and
update through Obtainium.

## v1.0.15

- **Energy VAD deleted — no fallback**: the hand-written detector is gone;
  WebRTC VAD is the only voice activity detector. If its native library
  cannot initialize, the recording session fails fast (before the mic is
  even opened) with a clear localized error — „Voice detection is
  unavailable on this device / 此裝置無法使用語音偵測“ — instead of silently
  recording without auto-stop.
- **Error contract tested**: the wrapper contains native failures (a broken
  native call reads as silence, never a crash), trims partial frames before
  the native call, recreates the native instance on reset, and close is
  idempotent. AudioRecorder tests cover the VAD-failure path, successful
  capture with a finalized WAV, and cancel leaving the header unpatched.
  (177 tests; the 2 real-native speech-classification tests are skipped in
  the JVM unit run — the Android .so cannot load there — and run on device.)

## v1.0.14

- **Proper VAD algorithm**: the hand-tuned energy detector is replaced with
  Google's **WebRTC VAD** (GMM-based, the same algorithm Chrome / Google
  Meet use) via the android-vad library. 20 ms frames @ 16 kHz, NORMAL mode
  (never miss soft-but-continuous speech), built-in 300 ms silence hangover
  + 50 ms speech debounce. The energy detector remains only as a fallback
  if the native library cannot load.
- **Built-in noise reduction**: Android's `android.media.audiofx
  NoiseSuppressor` is now attached to the recording session when the device
  supports it, denoising the captured signal — and therefore the uploaded
  WAV — before it reaches the transcription API. No-op where the platform
  already applies noise suppression (or doesn't support the effect).

## v1.0.13

- **Auto-stop no longer fires mid-speech**: the VAD threshold was
  `noiseFloor + 8 dB` and the noise floor ratcheted upward whenever a frame
  read as silence — including soft-but-continuous speech — so the detector
  could lock onto "silence" mid-sentence and the no-speech timeout (8 s) or
  the endpoint timer (2.5 s) cut dictation off. The margin is now 5 dB, the
  absolute floor -50 dBFS, the steady-state noise floor is capped at
  -45 dBFS (worst case: ambient itself reads as speech and you submit by
  tap — never a mid-speech cut), and the speech hangover is 300 ms so
  breathing / consonants / short phrase breaks never feed the silence
  timer.
- **Speaking ring clearly visible**: the meter band was recalibrated to the
  level Google's own speech pipeline uses — Android's AMR-WB VAD treats
  **-26 dBov as nominal speech level** — shifting the band down to
  [-55, -30] dBFS. Normal speech now drives the meter near full (~0.93)
  instead of ~0.3–0.5, soft speech ~0.6, silence → 0. The ring now has a
  minimum alpha as soon as speech-level energy appears (0.35 + level × 0.65),
  full brightness at 1.0, starts at 0.55 scale, and the stroke is thicker
  (6 → 9 dp).

## v1.0.12

- **Accidental taps are discarded**: a capture shorter than 0.5 s (e.g. a
  stray tap on the panel) is deleted without calling the transcription API —
  the panel silently goes back to listening instead of committing garbage.
- **"No words detected" error**: when the transcript comes back empty or
  without meaningful text (whitespace/punctuation-only, or fewer than two
  letters/digits/CJK characters — e.g. „嗯“), the app no longer commits it;
  it shows a clear **No words detected / 沒有偵測到任何文字** error, and
  tapping retries with a fresh recording (never re-transcribes the same
  noise).
- **Re-tap starts a fresh session**: after a transcript is committed and the
  IME returns to the previous keyboard, tapping the voice key again starts
  a new listening session — the controller no longer gets stuck in the
  stale „Transcribing…“ state (the IME can skip onFinishInputView when it
  auto-returns, so the session is now explicitly reset after delivery).

## v1.0.11

- **IME panel centering fixed**: the panel relied on `android:gravity="center"`
  on two FrameLayouts — but modern Android's FrameLayout **ignores that
  attribute** (children are positioned only by their own `layout_gravity`),
  so on current Android versions the whole cluster rendered top-left: mic
  and red speaking disc detached from each other, status/hint text
  left-aligned, ring drawn off-center. The centering now uses LinearLayout
  gravity (which is honored) plus `layout_gravity` on the ring/mic/spinner,
  and the text column spans the full panel width so everything stays
  centered even when an error hint wraps wide.
- **Speaking ring is a real ring now**: it was a solid red disc (104dp oval)
  painted over the panel; it's now a hollow 6dp-stroke ring around the mic,
  matching the Google voice-style design.
- Verified on an API 35 emulator: mic center within 1px of panel center
  (was ~200px off before), settings dark mode, and recognition panel all
  render as designed.

## v1.0.10

- **Settings page works in system dark mode**: the app previously had no
  night-mode resources, so HyperOS force-dark inverted the hard-locked Light
  theme and rendered the page black-on-black (dark text on the inverted
  black background). The settings (and base) themes now use AppCompat
  DayNight with a designed `values-night` palette that matches the always-
  dark recognition/IME panel — dark background, light text, teal section
  headers — and the app opts out of force dark (`forceDarkAllowed=false`)
  so the system never mangles it again.
- **IME mic and gear icons are white again**: the icons were black because
  `app:tint` is AppCompat-only and is silently ignored when the system
  LayoutInflater inflates the IME input view (a Service, not an
  AppCompatActivity). The IME layout now uses the framework `android:tint`
  and `GptVoiceIme` applies `imageTintList` explicitly in code (belt and
  braces), so the white mic / dim gear show on the dark panel.
- **Settings visuals use semantic colors**: background / text / dim text /
  section-header accent / input hints now come from colors with dedicated
  day and night values, and the chevron rows use framework `android:tint`.

## v1.0.9

- **IME transcription state rendering fixed**: state callbacks are now
  dispatched on the main thread (the panel could previously miss updates when
  a callback arrived off-thread) and stale callbacks from an older IME session
  are discarded via a session-generation counter. The session also survives an
  input-view restart (e.g. switching away and back mid-transcription), so the
  panel keeps reflecting the in-flight session instead of freezing.
- **Returning to the previous keyboard fixed**: the auto-return after commit /
  cancel is now guaranteed to happen exactly once — no more double-switch
  bouncing between keyboards. If committing the transcript fails at the last
  moment (editor focus lost), the IME stays open and retries; if the editor
  connection is still gone, the transcript is retained on the panel so a tap
  commits it once focus returns, and it survives IME restarts. If the keyboard
  switch itself doesn't finish, the panel hides itself rather than stranding
  the user.
- **Mic intensity meter fixed**: the meter was rewritten from a WebRTC-style
  peak-hold (which saturated to full on a single loud sample — clicks, taps,
  background noise) to per-frame RMS mapped over a voice-calibrated dBFS band
  with fast attack and slower release. Quiet, normal, and loud speech now read
  distinctly and the display follows sustained speech instead of flickering;
  calibration is validated up front (ceiling > floor, attack/release in 0..1).

## v1.0.8

- **Google-style IME panel geometry**: the speaking ring now scales from its
  true center (the old pivot was set before layout, so the ring grew from its
  top-left corner and never aligned with the mic). The mic + ring live in a
  fixed-size concentric container, with the status/hint text centered below
  and width-capped — no more overlap with the mic or clipping at the panel
  edge on short keyboards.
- **Transcribing signal**: the panel now shows a spinner plus a simple
  “轉譯中… / Transcribing…” status while the audio is being transcribed (was a
  bare status text with the ring abruptly disappearing).
- **Auto-stop no longer fires mid-speech**: the VAD/endpoint pipeline gained a
  ~200ms speech hangover that bridges micro-gaps inside words and phrases
  (only real silence can start the endpoint clock), the silence debounce was
  raised to ~100ms, and the speech margin was lowered (10→8 dB) so softer
  speech stays classified as speech.
- **Noise-aware default transcription prompt**: per the current gpt-transcribe
  guidance (prompt = free-form context about the recording; don't restate the
  transcription task), the default prompt is now a short description of the
  recording setting — single-speaker dictation with possible background noise
  and other people's voices — in both Traditional Chinese and English.

## v1.0.7

- **IME panel visuals**: gear and mic are pure white on the dark panel;
  the gesture/nav bar is painted with the panel color (no more white bar
  under the keyboard); system-bar insets keep content clear of the
  language-switch bar.
- **Google-voice-style speaking ring**: a red ring around the mic appears
  only while speaking and expands/brightens with the input level — normal
  speech reaches full size (ceiling at ~-20 dBFS); silent = invisible.
- **Auto-stop range extended**: 1.0–5.0 s in 0.2 s steps (was 1.0–3.0),
  default 2.5 s (was 1.8) so natural pauses during dictation no longer
  trigger early submission. Legacy values migrate as before.

## v1.0.6

- **Classified IME errors**: failures are now typed (no API key, recording
  failed, auth, rate-limited, server, API error, timeout, network, protocol)
  and shown with the matching localized message. Settings-fixable errors
  (no key / auth) open Settings on panel tap; transient errors retry.
- **Test expansion**: error classification for every failure path, panel-tap
  semantics, exact state sequences, cancel-during-in-flight-transcription
  race, retry reuses the same WAV, and a real integration test — controller
  + OpenAITranscriber against MockWebServer end to end (161 tests, lint 0).

## v1.0.5

- **IME panel redesign (whisperIME-style)**: minimal voice-only panel — large
  mic, level meter, status/hint, small gear. The Done button is gone:
  **tapping anywhere on the panel submits** (and retries after an error); the
  IME auto-returns to the previous keyboard after committing, and the back
  key cancels + returns.
- **Insets**: system-bar / language-bar insets are applied to the input view
  (whisperIME-style margins), so content no longer collides with the
  language-switch bar / arrows.
- **Robustness**: null-safe IME window token (was a crash path during
  dismissal), guarded taps/lifecycle with logging, coroutine scope cancelled
  on destroy, InputConnection-null retry before returning to the keyboard.
- **Mic permission**: the IME cannot request permissions itself (services
  can't) — when missing it guides to Settings, which now requests
  RECORD_AUDIO on open.

## v1.0.4

- **FIX: voice IME was never registered** — the IME service was missing the
  required `<intent-filter><action android:name="android.view.InputMethod"/></intent-filter>`
  (mandatory per the official "Create an input method" docs; the system
  enumerates IMEs by that intent). Without it the IME never appears in Manage
  keyboards on any device. This also explains why it was missing on the POCO
  F7 / HyperOS regardless of R8 or install path.
- Regression test added: the IME service must resolve
  `android.view.InputMethod` (the exact query InputMethodManagerService
  uses). R8 minification re-enabled (v1.0.3 ruled it out).

## v1.0.3

- **R8 minification disabled for release builds** (suspected interaction with
  IME registration on HyperOS — the voice keyboard did not appear in Manage
  keyboards with R8 on). APK size returns to ~3.2 MB until the on-device
  cause is confirmed; the fix or a re-enable with keep rules will follow.

## v1.0.2

- **Mic meter scale fixed (K-system convention)**: the meter maps peak dBFS
  over floor -40 dBFS → ceiling -20 dBFS, so silence/ambient/whisper read 0,
  quiet speech ~half, and normal AGC'd speech (~-20 dBFS) reads full.
- Verified against research: gpt-transcribe/Whisper does not normalize input
  loudness internally; the recommended speech level is roughly -20 to -16
  dBFS RMS with peaks -10 to -6 dBFS and no clipping. The VOICE_RECOGNITION
  platform AGC delivers exactly this band — no app-side gain is added.

## v1.0.1

- **APK size**: release builds now run R8 minification + resource shrinking
  (3.2 MB → ~0.9 MB) with all manifest components verified intact; dead code
  removed (unused recorder property, unformatted error string fixed).
- **Mic meter calibration**: normal speech now reads near full. The meter
  maps the peak dBFS over a voice-calibrated band (-45 dBFS floor, -18 dBFS
  ceiling): quiet speech moves the meter, normal AGC'd speech ≈ full, loud
  saturates.

## v1.0.0

- **Voice input method (IME)**: the app now also ships a normal, visible
  voice IME (`GptVoiceIme`) in the keyboard cycle. The input view is a
  voice-only panel — no keys. Reached via the globe key or by tapping the
  panel (switchToNextInputMethod); after commit the IME auto-returns to the
  previous keyboard (`switchToPreviousInputMethod`). Because the IME window
  layer is above chat-head overlays, dictation works in Messenger bubbles.
- Panel chrome: Done button / auto-stop submit; X-like escape via panel tap
  or back key (both switch to the next IME); gear opens Settings.
- The existing ACTION_RECOGNIZE_SPEECH Activity (SwiftKey mic) is unchanged.
- Tests: ImeVoiceController state machine with fake recorder/transcriber,
  IME manifest/metadata invariants (visible non-auxiliary subtype, switching
  support).

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
