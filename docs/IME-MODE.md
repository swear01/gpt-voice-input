# IME-mode (voice input method) — design plan for v0.2.0

Status: **proposal** (tracked in GitHub issue #8). Not yet implemented.

## Motivation

The recognition panel is an Activity window (`TYPE_APPLICATION`). Android places
chat-head bubbles (`TYPE_APPLICATION_OVERLAY`) **above all activity windows but
below the input-method window** (official `WindowManager.LayoutParams`
documentation). Therefore Messenger chat heads can cover the bottom panel —
Gboard-style voice works there only because it is an IME, and the system
recognizer only survives because it is full-screen.

Raising an activity window above bubbles would require `SYSTEM_ALERT_WINDOW`
(floating-overlay permission, deliberately out of scope). The only in-scope way
to be above chat heads is the **input-method window layer**.

## Goal

A minimal **voice input method** — not a keyboard, not a launcher, not a full
IME product:

- no keys / candidates / composing — the input view is only the existing
  voice panel (mic level, Listening…, Transcribing…, error),
- selected from the keyboard switcher, it records and transcribes, commits the
  final text via `InputConnection.commitText`, then returns to the previous
  input method (SwiftKey) automatically,
- coexists with the existing `ACTION_RECOGNIZE_SPEECH` Activity (SwiftKey's mic
  still works as today; the IME covers chat heads and any editor).

## Technical basis

- `InputMethodService` provides the input-method window layer
  (`TYPE_INPUT_METHOD`), which is above `TYPE_APPLICATION_OVERLAY`.
- `InputMethodService.commitText` inserts the transcript into the focused
  editor — the same guarantee the Activity result path relies on, but directly
  on the `InputConnection`.
- `InputMethodService.switchToPreviousInputMethod()` (public since API 28)
  returns to the previously used IME after commit/cancel — the "goes back to
  SwiftKey" UX.
- All existing components are reused as-is: `AudioRecorder` (raw WAV +
  analysis/VAD/meter), `MicLevelEstimator`, `VadProcessor`,
  `EndpointDetector`, `OpenAITranscriber`, `AppConfig`, `SettingsStore`,
  `ImportedProfileStore`, `SettingsBackup`, `SecureApiKeyStore`.

## New components

```
app/src/main/kotlin/org/gptvoiceinput/ime/
├── GptVoiceIme.kt            InputMethodService: voice panel, lifecycle,
│                             commit + switch-back
└── ImeVoiceState.kt          shared session state (LISTENING/PROCESSING/…),
│                             duplicate-session guard, result delivery via
│                             InputConnection (mirrors RecognitionActivity)
res/xml/method.xml            input-method metadata (settingsActivity →
                              SettingsActivity, one subtype)
```

Manifest:

```xml
<service
    android:name=".ime.GptVoiceIme"
    android:exported="true"
    android:permission="android.permission.BIND_INPUT_METHOD">
    <meta-data
        android:name="android.view.im"
        android:resource="@xml/method" />
</service>
```

The input view reuses the bottom-panel layout (voice-only; no keys).

## UX flows

### A. Existing flow (unchanged)
SwiftKey mic → `ACTION_RECOGNIZE_SPEECH` → panel Activity → `EXTRA_RESULTS`.
Still below chat heads (documented limitation).

### B. New IME flow (above chat heads)
1. User enables "GPT Voice Input" in system settings and selects it once from
   the keyboard switcher.
2. The voice panel appears (IME layer — above bubbles).
3. Speak; tap panel or auto-stop → transcribe (same pipeline).
4. Final text committed via `commitText`; panel hides;
   `switchToPreviousInputMethod()` returns to SwiftKey.
5. Cancellation/back → hide + switch back, no text inserted.

## Constraints that must be stated

- An input method **appears in the keyboard switcher** (system requirement for
  any IME). It has no keyboard UI, but it is selectable there.
- SwiftKey's mic button launches the **Activity**, not the IME — the two entry
  points are complementary, not interchangeable.
- No new permissions: still only `RECORD_AUDIO` + `INTERNET`.

## Non-interference with daily typing (researched)

Requirement: the voice IME may live in the keyboard-switcher cycle, but must
never disturb normal typing with SwiftKey.

Verified against AOSP `attrs.xml` / `InputMethodInfo` /
`InputMethodManagerService`:

- **Not running = zero interference.** When SwiftKey is active, our IME is not
  selected and renders nothing. It only ever shows its UI when the user
  switches to it.
- **Voice-only surface.** No keys, no autocorrect/suggestions, no composing —
  nothing that could hijack normal text input.
- **Auto-return.** After `commitText` (or cancel), call
  `switchToPreviousInputMethod()` (public API 28+) to hand control back to
  SwiftKey, so the user is never stuck on a keyless IME.
- **Escape hatch.** `android:supportsSwitchingToNextInputMethod="true"` + a
  globe/switch button on the panel (`switchToNextInputMethod(false)`) so an
  accidental switch is one tap away from returning.

Optional hardening flags (researched; not chosen by default):

- `android:showInInputMethodPicker="false"` removes the IME from the switcher
  entirely — but then it can only be invoked from system settings, which
  conflicts with the desired in-cycle availability.
- `android:isAuxiliary="true"` marks it as supplementary (the Gboard-voice
  pattern: invoked on demand, never a daily target) — not needed here since
  we auto-return and expose the globe escape.

## Acceptance criteria

- [ ] IME selectable; input view contains only the voice panel (no keys)
- [ ] Panel appears above Messenger chat heads
- [ ] Speech → transcript committed into the focused editor
- [ ] Auto-return to previous IME after commit and after cancel
- [ ] Mic level meter, auto-stop, VAD, no-speech timeout all work in the IME
- [ ] Settings (API key, transcription profile, import/export) reachable from
      the IME and shared with the Activity path
- [ ] API key / profile / custom terms / auto-stop persisted as today
- [ ] Existing `ACTION_RECOGNIZE_SPEECH` path is a regression-free duplicate
- [ ] No background recording: leaving the IME session cancels cleanly
- [ ] Localization follows (reuse string resources)
- [ ] Tests: manifest/metadata, IME state machine with a fake
      `InputConnection`, shared pipeline regression; real-device chat-head
      verification required

## Version

This is a minor-version change: target **v0.2.0** (existing signing identity,
package id, Obtainium update path preserved).
