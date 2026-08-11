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

`res/xml/method.xml`:

```xml
<input-method xmlns:android="http://schemas.android.com/apk/res/android"
    android:settingsActivity="org.gptvoiceinput.ui.SettingsActivity"
    android:supportsSwitchingToNextInputMethod="true">
    <subtype
        android:label="@string/app_name"
        android:imeSubtypeMode="voice"
        android:isAuxiliary="true"
        android:showInInputMethodPicker="false" />
</input-method>
```

The input view reuses the bottom-panel layout (voice-only; no keys) plus an X
(close) button instead of any globe/keyboard affordance.

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

## Non-interference with daily typing (researched, decided: fully hidden)

Requirement: the voice IME must be invisible in the keyboard switcher and
never disturb normal typing with SwiftKey. Decided design — **both extreme
flags**:

- `android:isAuxiliary="true"` — auxiliary subtype; the globe cycle only
  rotates non-auxiliary IMEs (`InputMethodManagerService`:
  `hasMultipleSubtypesForSwitcher(true /* nonAuxOnly */)`).
- `android:showInInputMethodPicker="false"` — excluded from both the globe
  cycle and the picker (`InputMethodSubtypeSwitchingController`: `if
  (!imi.shouldShowInInputMethodPicker()) continue;`).

Verified against AOSP `attrs.xml`, `InputMethodInfo`, `ImeSubtypeSwitchingController`,
`InputMethodManagerService`.

Consequences (stated honestly):

- The IME never appears in the switcher or picker — zero interference.
- Apps **cannot programmatically switch input methods** (platform security).
  The only invocation path is System settings → Languages & input →
  On-screen keyboard → tap GPT Voice Input. We will expose a shortcut button
  in our Settings that opens `Settings.ACTION_INPUT_METHOD_SETTINGS` so the
  trip is one screen.
- The existing `ACTION_RECOGNIZE_SPEECH` Activity (SwiftKey mic) remains the
  daily entry point; the hidden IME is the chat-head / compatibility path.

## Panel chrome: close (X) instead of globe

Like Google's voice dictation: the panel has a single **X (close)** button
and the **back key** both mean "done — return to the keyboard":

- X button → `switchToPreviousInputMethod()` (falls back to
  `requestHideSelf(0)` when there is no previous IME).
- Back key (`onKeyDown(KEYCODE_BACK)`) → same return behavior, not just hide.
- After `commitText` the same auto-return runs, so the user is never stranded
  on a keyless IME.

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
