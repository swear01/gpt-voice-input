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
  Android reasonably exposes (`UNPROCESSED` source when available, else
  `VOICE_RECOGNITION`). No noise suppression, no AGC, no resampling on the
  upload path. Audio processing (a light energy VAD) exists **only** on a
  copied side channel for end-of-speech detection.
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

GPT Voice Input intentionally has **no launcher entry** — it behaves like a
small system component, so you will not find it in the app drawer. Setup
happens through SwiftKey:

1. Install with Obtainium (above).
2. In SwiftKey: **Settings → Voice typing** — disable SwiftKey's own Multimodal
   Voice Typing so SwiftKey routes mic presses to the system speech recognizer.
3. Open any text field.
4. Tap SwiftKey's microphone button. Android launches GPT Voice Input.
5. If no API key is configured yet, GPT Voice Input shows the no-key state.
6. Tap **Open Settings** (or the ⚙ gear in the panel).
7. Enter your OpenAI API key.
8. Save and go back.
9. Dictate. Tap the panel to submit, or wait for auto-stop.

If Android shows a speech-recognizer chooser (some devices/SwiftKey builds do),
select **GPT Voice Input** and choose *Always* if the OS offers that option.

## How it works

```
Microphone
    ↓
AudioRecord (UNPROCESSED / VOICE_RECOGNITION)
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

## Configuration profiles

Transcription configuration is layered, each layer overriding the previous:

```text
1. config/default.json        generic public defaults (always shipped)
2. config/local.json          deployment overlay (embedded at build time, optional)
3. imported profile           Settings → Advanced → Import (runtime, on-device)
4. custom terms               Settings → Advanced → Custom terms (runtime)
        ↓
   effective TranscriptionProfile sent to gpt-transcribe
```

- `config/default.json` ships inside the APK with neutral, generic defaults
  (Traditional Chinese/English code switching, neutral context, no keywords).
- Deployments can overlay a personal profile at **build time**:
  `cp config/personal.example.json config/local.json` (gitignored). CI injects
  it via the `GVI_DEPLOYMENT_CONFIG_BASE64` secret instead of committing it.
- A runtime **imported profile** (Settings → Advanced → Import) overrides
  languages/context and merges keywords — useful to move a setup between
  devices or to apply a fork's profile.

**API keys never live in config** — they are entered at runtime in Settings and
stored encrypted in the Android Keystore.

### Settings import / export

Under **Settings → Advanced → Profile & settings** you can back up and restore
the non-secret configuration: expected languages, transcription context,
profile keywords, auto-stop, and custom terms. The file is `schemaVersion: 1`
JSON. **API keys are intentionally excluded from backups.**

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
`GVI_KEY_PASSWORD`), optionally injects the deployment profile from
`GVI_DEPLOYMENT_CONFIG_BASE64`, runs unit tests + Android lint, builds,
verifies the signature with `apksigner`, and attaches
`gpt-voice-input-v<tag>.apk` to a GitHub Release.

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

Pushing a tag (e.g. `v0.1.1`) triggers the release workflow. The APK asset
name is predictable (`gpt-voice-input-v0.1.1.apk`) so Obtainium can track new
versions. The signing certificate never changes, so seamless updates work
without uninstalling.

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
├── ui/RecognitionActivity.kt      ACTION_RECOGNIZE_SPEECH entry, states, retry
├── ui/SettingsActivity.kt         gear-button settings (key, auto-stop, terms)
├── audio/AudioRecorder.kt         split pipeline: raw upload + analysis copy
├── audio/VadProcessor.kt          adaptive noise floor, energy VAD (analysis only)
├── audio/EndpointDetector.kt      WAITING_FOR_SPEECH → IN_SPEECH → ENDPOINT_CANDIDATE
├── audio/WavWriter.kt             44-byte RIFF writer (little-endian)
├── net/OpenAITranscriber.kt       gpt-transcribe multipart client (OkHttp)
├── config/AppConfig.kt            default → deployment → imported → custom terms
├── config/SettingsStore.kt        auto-stop, custom terms
├── config/ImportedProfileStore.kt runtime imported profile (non-secret)
├── config/SettingsBackup.kt       schemaVersion-1 import/export + validation
└── security/SecureApiKeyStore.kt  Android Keystore AES/GCM key storage
config/
├── default.json                   neutral public defaults (shipped)
└── personal.example.json          deployment overlay example (copy → local.json)
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

## License

Apache License 2.0 — see [LICENSE](LICENSE). Microphone/settings icon paths
are from the Material Design icon set (Apache-2.0).
