# Manual speech testing corpus

Dictation accuracy must be measured, not assumed. This corpus targets
acoustically ambiguous terminology (acronyms, mixed letter/number names,
product names that ASR commonly normalizes wrongly).

The terms below are synthetic placeholders — replace them with terms relevant
to your own dictation when testing your personal profile.

## Corpus

Read each line aloud naturally, as dictation.

1. 我現在主要是用 ExampleTool，不是 Example Tool。
2. ACME_TERM 是透過 ACME_CORP 跟另一支 tool 溝通的。
3. ZXQ-17 跟 ACME_CORP 是不同的東西。
4. 這個 bug 出現在 XY-42 的 QRS session。
5. 我想用 ExampleSAT 驗證這個 predicate。
6. 這個 benchmark 是 SVX-9 的。
7. 我們可以用 ABC-X 搭配 IC-7 或 PD-3。
8. XY-42 的輸出要交給 model checker。
9. ACME_CORP 跟 ExampleTool 都有 EDA tools。
10. 這個 mixed-style design 要跑 formal verification。
11. ExampleTool 接到 ACME_TERM，另外一個 agent 走別條路。

## Methodology

For every line, record once and compare configurations (re-record each config
on the same device/room/mic):

| Run | Profile | Runtime keywords |
|-----|---------|------------------|
| A   | generic default | none |
| B   | custom context, no keywords | none |
| C   | custom context + keyword list | none |

Score each line as correct / partially wrong / wrong, noting the exact error.

## Rules of engagement

- **Do not assume hints help.** Compare A/B/C per line.
- Remove keywords that demonstrably hurt accuracy (hints can bias output).
- Add a new keyword only after observing a **real recurring** failure on that
  term, and verify it doesn't regress neighboring lines.
- Do **not** add easy/common terms (`Python`, `Android`, `API`, `agent`,
  `model`, …) unless testing demonstrates a recurring problem.

## Device checklist (SwiftKey integration)

1. SwiftKey mic launches GPT Voice Input (no chooser, no keyboard switching).
2. Recording starts immediately.
3. Tap-to-submit inserts text into the original field.
4. Auto-stop inserts text without tapping.
5. Auto-stop OFF disables auto-submit.
6. Back during listening: no API request, nothing inserted.
7. Back during transcription: cancelled, no duplicate billable POST.
8. Home / screen lock / app switch: no background recording, no insertion.
9. Rotation: recording continues uninterrupted.
10. Network loss / timeout / 401 / 429 / 5xx: error panel with Retry/Cancel;
    Retry reuses the recorded WAV. 401 shows Cancel + Open Settings.
11. Microphone permission denied: guidance screen; nothing inserted.
12. Raw audio: uploaded WAV must NOT pass through the VAD/noise path (verified
    by architecture: separate analysis copy; no AudioEffect attached).
13. Chat heads / floating windows (e.g. Messenger bubbles): the bottom panel
    is an Activity window (TYPE_APPLICATION), which is BELOW
    TYPE_APPLICATION_OVERLAY windows — a bubble can cover it. This is
    inherent to non-IME voice panels (IME windows sit above overlays; the
    system recognizer is full-screen so it stays usable). Workaround: move or
    dismiss the bubble while dictating. See README troubleshooting.

## Regression checklist (v0.1.3)

### Settings

- [ ] gear button opens Settings without crash
- [ ] no-key "Open Settings" opens Settings without crash
- [ ] Back returns cleanly (no duplicate Activity, no invisible recording)
- [ ] saved API-key state remains set after reopening Settings
- [ ] Languages / Context / Keywords fields reload after reopening Settings
- [ ] auto-stop slider position reloads correctly
- [ ] opening Settings while LISTENING cancels the session (no mic leak)
- [ ] no Advanced section / no Effective Configuration dump present
- [ ] editing Languages/Context/Keywords and Save persists; invalid language
      code shows an error and changes nothing

### Import/export

- [ ] export produces valid `format: gpt-voice-input-settings`, `schemaVersion: 1` JSON
- [ ] safe export never contains the API key
- [ ] full backup contains the API key only after the plaintext warning
- [ ] import round-trip reproduces the same visible/effective configuration
- [ ] import with `secrets.openAiApiKey` stores the key (no plaintext file kept)
- [ ] import without `secrets` preserves the stored API key
- [ ] placeholder API key (`REPLACE_WITH_YOUR_OPENAI_API_KEY`) is rejected atomically
- [ ] malformed JSON produces a clear error and changes nothing
- [ ] unsupported (newer) schema version produces a clear error
- [ ] invalid auto-stop / bad language/keyword types produce errors, no state change
- [ ] imported keywords appear immediately in the Keywords field
- [ ] Reset transcription profile restores generic defaults (key/auto-stop kept)
- [ ] Clear API key works and requires confirmation

### Runtime config vs. updates

- [ ] imported profile, API key, keywords and auto-stop survive an update
      (same package + certificate, installed over)
- [ ] the public release APK contains only generic `default.json` — no personal
      profile, no keywords, no API key (CI asserts this structurally)
- [ ] changing `default.json` in a future release does not erase the imported
      profile (covered by the `imported profile overrides a changed default`
      unit test)

### Distribution

- [ ] Obtainium detects new releases
- [ ] update installs over the previous version without a signature mismatch
      (same package, higher versionCode, same signing certificate)
- [ ] settings/app data survive the update
