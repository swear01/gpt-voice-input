# GPT Voice Input

A minimal, open-source Android **voice-input provider**: SwiftKey mic → record
speech → OpenAI `gpt-transcribe` → final text → SwiftKey.

This is **not a keyboard**. It has no launcher entry, no floating button, no
Accessibility service, no background dictation. It is a small system component
that answers Android's standard `ACTION_RECOGNIZE_SPEECH` intent.

```
press SwiftKey mic → speak → tap or auto-stop → GPT Transcribe → text appears
```

## Why

- **Transcription quality** — `gpt-transcribe` is the current OpenAI
  transcription model, with free-form context, keyword hints, and multiple
  expected input languages.
- **Raw audio to OpenAI** — the uploaded WAV is the least-processed signal
  Android reasonably exposes while still being transcribable:
  `VOICE_RECOGNITION` is preferred because the platform tunes it for speech
  recognition (AGC on most devices); a raw `UNPROCESSED` signal has no gain
  and is typically too quiet for gpt-transcribe (near-zero meter + failed
  recognition on real devices). `UNPROCESSED` remains a fallback where
  `VOICE_RECOGNITION` misbehaves. No effects (NoiseSuppressor etc.) are
  attached to the session.
- **Minimal interaction** — tap anywhere to submit; auto-stop is optional.
- **Minimal footprint** — one Activity, a handful of classes; dependencies are
  AndroidX core, AppCompat, coroutines, OkHttp, and org.json.

## Requirements

- Android 8.0+ (API 26)
- Microsoft SwiftKey with **Multimodal Voice Typing disabled**
- An OpenAI API key (`gpt-transcribe` is billed per audio input)

## Installation (Obtainium)

Normal installs and updates go through **Obtainium** — no ADB, no manual APK
downloads.

```text
Obtainium
→ Add App
→ https://github.com/swear01/gpt-voice-input
→ Install
```

Obtainium watches the GitHub Releases of this repository and offers updates
when a new version is published. Because every release is signed with the same
long-lived certificate, updates install **over** the previous version and your
settings survive.

> Manual APK downloads are only for troubleshooting — see
> [Advanced / manual installation](#advanced--manual-installation-troubleshooting).

## First-time setup

GPT Voice Input intentionally has **no app-drawer / launcher icon** — it behaves
like a small system component, so you will not find it in the app drawer
(ACTION_MAIN + CATEGORY_INFO is used for a package front door, never
CATEGORY_LAUNCHER). Setup happens through SwiftKey:

You can also open Settings from **Android Settings → Apps → GPT Voice Input →
Open** on systems that expose that action.

1. Install with Obtainium (above).
2. In SwiftKey: **Settings → Voice typing** — disable SwiftKey's own Multimodal
   Voice Typing so SwiftKey routes mic presses to the system speech recognizer.
3. Open any text field.
4. Tap SwiftKey's microphone button. Android launches GPT Voice Input.

### Generic user

5. Tap **Open Settings** (or the ⚙ gear in the panel).
6. Enter your OpenAI API key.
7. Save and go back.
8. Dictate. Tap the panel to submit, or wait for auto-stop.

### Personal-config user

Instead of typing the API key on the phone, import a settings file that already
contains your key and profile (see below):

5. Tap **Open Settings** (or the ⚙ gear in the panel).
6. **Profile & backup → Import settings**.
7. Choose your personal settings file (e.g. `gpt-voice-input-personal.json`).
8. Confirm the summary. If the file contains an API key, no keyboard entry of
   the key is necessary.
9. Dictate.

If Android shows a speech-recognizer chooser (some devices/SwiftKey builds do),
select **GPT Voice Input** and choose *Always* if the OS offers that option.

## Voice input method (keyboard cycle)

Since v1.0.0 the app also registers a **voice input method**: a normal,
visible IME in the keyboard cycle whose input view is the voice panel only
(no keys). It is reached via the globe key or by tapping the panel
(`switchToNextInputMethod`), and after committing a transcript it returns to
the previous keyboard automatically.

```text
globe key / panel tap
  → GPT Voice Input voice panel (above chat heads)
  → speak (meter + auto-stop + VAD as usual)
  → commitText → auto-return to the previous keyboard
```

Because the IME window layer is above `TYPE_APPLICATION_OVERLAY`, dictation
works in Messenger chat heads and similar floating windows. The
`ACTION_RECOGNIZE_SPEECH` Activity (SwiftKey mic) remains available and
unchanged.

## How it works

```
Microphone
    ↓
AudioRecord (VOICE_RECOGNITION / UNPROCESSED)
    ↓
raw PCM
    ├──────────────────────────→ WAV → OpenAI gpt-transcribe
    │
    └→ copied frames
          ↓
      downsample → VAD (analysis only)
          ↓
      endpoint detector (1.0–3.0 s silence, or OFF)
```

- Manual tap **always** wins: it calls the same submit path as auto-stop.
- Silence timer never starts before speech is detected.
- No-speech timeout (~8 s) cancels gracefully without an API request.
- Hard cap of 120 s per recording.
- Timeouts and network failures surface as **Couldn't transcribe → Retry /
  Cancel**; the already-recorded WAV is reused on retry. Ambiguous POST
  failures are never replayed automatically.

## Configuration

Transcription configuration is layered:

```text
1. generic default.json asset   neutral public defaults (always shipped)
2. runtime imported profile     what you edit/import in Settings
        ↓
   profile sent to gpt-transcribe
```

### Settings → Transcription

The profile actually sent to the transcription API is directly editable:

```text
Languages              e.g. zh-tw, en (comma separated, validated)
Context                the transcription prompt
Keywords / special terms   one per line
```

Editing these fields and pressing Save creates a runtime profile override that
persists in app-private storage and survives ordinary updates (same package,
same signing certificate). Only an uninstall or data-clear removes it.

### Settings → Profile & backup

| Action | Content | API key |
|--------|---------|---------|
| Import settings | restores languages/context/keywords, auto-stop; may also import a key | optional |
| Export settings | portable non-secret backup (`gpt-voice-input-settings.json`) | **never** |
| Export full backup | personal backup (`gpt-voice-input-personal.json`) for moving to a new phone | **plaintext, warned** |
| Reset transcription profile | restores generic defaults (key and auto-stop kept) | — |

Files are `schemaVersion: 1` JSON in the `gpt-voice-input-settings` format.
Import is validated up front (JSON, format, schema version, language codes,
auto-stop grid, types, placeholder-key rejection) and applied atomically after
you confirm the summary. Newer unsupported schema versions are rejected with a
clear message. The full backup warns before writing because it contains your
OpenAI API key in plaintext — store it only in a private location.

### Keeping a private profile outside the repository

This project is intentionally generic. You can keep your own private JSON
anywhere outside the repository (e.g. a cloud drive) and import it on each new
device. A minimal example with placeholder values:

```json
{
  "format": "gpt-voice-input-settings",
  "schemaVersion": 1,
  "secrets": {
    "openAiApiKey": "REPLACE_WITH_YOUR_OPENAI_API_KEY"
  },
  "profile": {
    "expectedLanguages": ["zh-tw", "en"],
    "transcriptionContext": "Transcribe natural dictation faithfully.",
    "keywords": ["ExampleProductName", "ACME-Widget", "XYZ-42"]
  },
  "settings": {
    "autoStopSeconds": 1.8,
    "customTerms": []
  }
}
```

**API keys never live in the APK or in config assets** — they are entered or
imported at runtime and stored encrypted in the Android Keystore.

### Keyword philosophy

Keywords are hints, not a vocabulary list. Only add a term that is
acoustically ambiguous (acronyms, mixed letter/number names, product names
that ASR normalizes wrongly) — and only after observing a real misrecognition.

## Building

```bash
# Debug
./gradlew :app:assembleDebug

# Unit tests (VAD, endpoint state machine, WAV writer, config merge,
# OpenAI wire format against MockWebServer)
./gradlew :app:testDebugUnitTest
```

Requires JDK 17 and an Android SDK (compileSdk 35).

### Signing & release builds

Release builds are signed when a keystore is available; otherwise they build
unsigned (for local verification only — unsigned APKs won't install).

**Local:**
1. Create one long-lived signing key (all releases must share it so updates
   install seamlessly):
   ```bash
   keytool -genkeypair -v -keystore keystore/release.keystore \
     -alias gpt-voice-input -keyalg RSA -keysize 4096 -validity 10000
   ```
2. `keystore.properties` (gitignored) with `storePassword`, `keyAlias`,
   `keyPassword`.
3. `./gradlew :app:assembleRelease`

**CI:** the workflow in `.github/workflows/release.yml` reads the signing
secrets (`GVI_KEYSTORE_BASE64`, `GVI_STORE_PASSWORD`, `GVI_KEY_ALIAS`,
`GVI_KEY_PASSWORD`), runs unit tests + Android lint, builds, verifies the
signature with `apksigner`, asserts the versionCode strictly increased, and
attaches `gpt-voice-input-v<tag>.apk` to a GitHub Release. Releases are always
**generic** — personal configuration is runtime data, never injected by CI.

Keystores and passwords are **never** committed.

## Distribution

```text
Developer:                          User:
git tag vX.Y.Z                    Obtainium watches this repository
        ↓                                ↓
GitHub Actions (tests + lint)     detects a new GitHub Release
        ↓                                ↓
Signed APK                        Update (installs over the old version)
        ↓                                ↓
GitHub Release                    settings & data survive (same certificate)
```

Pushing a tag (e.g. `v0.1.4`) triggers the release workflow. The APK asset
name is predictable (`gpt-voice-input-v0.1.4.apk`) so Obtainium can track new
versions. The signing certificate never changes, so seamless updates work
without uninstalling.

### Chat heads / floating windows (e.g. Messenger bubbles)

When replying from a Messenger chat head, the recognition panel can be covered
by the bubble. This is a window-layer limitation, not a bug:

- The panel is an Activity window (`TYPE_APPLICATION`).
- Chat-head bubbles use `TYPE_APPLICATION_OVERLAY`, which Android places
  **above all activity windows** but **below the input-method window**
  (official `WindowManager.LayoutParams` documentation).
- IME-based voice input (Gboard / SwiftKey's own voice) therefore draws above
  bubbles; the system recognizer is a full-screen activity, so a bubble never
  hides it entirely. A small bottom-anchored non-IME panel is the one case
  that can be fully covered.

Workaround: move or dismiss the bubble while dictating. Making the panel
appear above bubbles would require either an input-method service or the
"display over other apps" overlay permission — both deliberately out of
scope for this project.

### Advanced / manual installation (troubleshooting)

Only for troubleshooting — the normal path is Obtainium:

1. Open the [Releases](https://github.com/swear01/gpt-voice-input/releases) page
   on the device.
2. Download the `gpt-voice-input-vX.Y.Z.apk` asset and open it.
3. Allow "install unknown apps" for the browser/file manager.

Manual installs use the same certificate as Obtainium installs; you can switch
between the two freely without losing data.

## Repository layout

```
app/src/main/kotlin/org/gptvoiceinput/
├── ui/RecognitionActivity.kt      ACTION_RECOGNIZE_SPEECH entry, bottom panel
├── ui/SettingsActivity.kt         OpenAI / Transcription / Recording / backup
├── audio/AudioRecorder.kt         split pipeline: raw upload + analysis copy
├── audio/VadProcessor.kt          adaptive noise floor, energy VAD (analysis only)
├── audio/EndpointDetector.kt      WAITING_FOR_SPEECH → IN_SPEECH → ENDPOINT_CANDIDATE
├── audio/WavWriter.kt             44-byte RIFF writer (little-endian)
├── net/OpenAITranscriber.kt       gpt-transcribe multipart client (OkHttp)
├── config/AppConfig.kt            default → runtime imported profile → legacy terms
├── config/SettingsStore.kt        auto-stop, legacy customTerms migration
├── config/ImportedProfileStore.kt runtime imported profile (app-private)
├── config/SettingsBackup.kt       versioned import/export + validation
└── security/SecureApiKeyStore.kt  Android Keystore AES/GCM key storage
app/src/main/assets/
└── default.json                   neutral generic defaults (the only shipped config)
scripts/
└── release-check-versioncode.sh   CI versionCode gate (previous-release lookup)
```

## Testing

See [docs/TESTING.md](docs/TESTING.md) for the manual speech corpus focused on
ambiguous terminology and the A/B methodology (no hints vs. context vs.
context + keywords).

## Security & privacy

- API key: runtime-entered, encrypted at rest (Android Keystore AES/GCM),
  never logged, never in the repo, never in config.
- Recordings: `cacheDir/current_recording.wav`, deleted after success, error,
  cancel, or destruction; stale files cleaned on next start. No history.
- No telemetry, no analytics, no account, no backend: the device talks
  directly to `api.openai.com`.
- `android:allowBackup="false"`.

## Prior art

[Dictate Keyboard](https://github.com/DevEmperor/DictateKeyboard) demonstrated
a practical system-wide Android voice-input integration and informed parts of
this project's interaction design. This is an independent implementation using
only Android and OpenAI public APIs; no Dictate source is copied or vendored,
and the project does not depend on `dictate-core`. If any material is ever
adapted, the Apache-2.0 notices of the source files will be preserved here.

## Changelog & third-party licenses

- [CHANGELOG.md](CHANGELOG.md) — per-version summary.
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) — bundled dependencies and
  icon licenses (Apache-2.0 project license in [LICENSE](LICENSE)).

## License

Apache License 2.0 — see [LICENSE](LICENSE) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
