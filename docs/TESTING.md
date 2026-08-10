# Manual speech testing corpus

Dictation accuracy must be measured, not assumed. This corpus targets
acoustically ambiguous terminology (acronyms, mixed letter/number names,
product names that ASR commonly normalizes wrongly).

## Corpus

Read each line aloud naturally, as dictation.

1. 我現在主要是用 Pi Agent，不是 PyAgent。
2. HAPI 這邊是透過 ACP 跟 Cursor 溝通。
3. MCP 跟 ACP 是不同的 protocol。
4. 這個 bug 出現在 AGY 的 PTY session。
5. 我想用 PySMT 和 MathSAT 驗證這個 predicate。
6. 這個 benchmark 是 SV-COMP 的。
7. 我們可以用 CEGAR 搭配 IC3 或 PDR。
8. BTOR2 的輸出要交給 model checker。
9. Synopsys 跟 Cadence 都有 EDA tools。
10. 這個 RISC-V Ibex design 要跑 formal verification。
11. DeepSeek 接到 OpenCode，Codex 則走另一個 agent。

## Methodology

For every line, record once and compare three configurations (same recording
can be replayed through the three configs only if you can re-upload the same
WAV — otherwise re-record each config on the same device/room/mic):

| Run | Deployment config | Runtime custom terms |
|-----|-------------------|----------------------|
| A   | none (pure default) | none |
| B   | personal context, no keywords | none |
| C   | personal context + keyword seed | none |

Score each line as correct / partially wrong / wrong, noting the exact error.

## Rules of engagement

- **Do not assume hints help.** Compare A/B/C per line.
- Remove keywords that demonstrably hurt accuracy (hints can bias output).
- Add a new keyword only after observing a **real recurring** failure on that
  term, and verify it doesn't regress neighboring lines.
- Known ambiguous mappings to watch:
  - `HAPI` → "happy"
  - `Pi Agent` → "PyAgent", "pie agent"
  - `Synopsys` → "synopsis"
  - `MathSAT` → "math SAT"
  - `BTOR2`, `IC3`, `RISC-V` → letter/number confusion
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
    Retry reuses the recorded WAV.
11. Microphone permission denied: guidance screen; nothing inserted.
12. Raw audio: uploaded WAV must NOT pass through the VAD/noise path (verified
    by architecture: separate analysis copy; no AudioEffect attached).

## Regression checklist (bugs found on real hardware — v0.1.1)

### Settings

- [ ] gear button opens Settings without crash
- [ ] no-key "Open Settings" opens Settings without crash
- [ ] Back returns cleanly (no duplicate Activity, no invisible recording)
- [ ] saved API-key state remains set after reopening Settings
- [ ] existing custom terms reload after reopening Settings
- [ ] auto-stop slider position reloads correctly
- [ ] opening Settings while LISTENING cancels the session (no mic leak)

### Import/export

- [ ] export produces valid `format: gpt-voice-input-settings`, `schemaVersion: 1` JSON
- [ ] safe export never contains the API key
- [ ] full backup contains the API key only after the plaintext warning
- [ ] import round-trip reproduces the same effective configuration
- [ ] import with `secrets.openAiApiKey` stores the key (no plaintext file kept)
- [ ] import without `secrets` preserves the stored API key
- [ ] malformed JSON produces a clear error and changes nothing
- [ ] wrong `format` produces a clear error and changes nothing
- [ ] unsupported (newer) schema version produces a clear error
- [ ] invalid auto-stop / bad language/keyword types produce errors, no state change
- [ ] imported profile affects the transcription configuration
- [ ] Clear imported profile removes only the profile (key/auto-stop/terms stay)
- [ ] Clear API key works and requires confirmation

### Runtime config vs. updates

- [ ] imported profile, API key, custom terms and auto-stop survive an update
      (v0.1.1 → next version, same package + certificate, installed over)
- [ ] the public release APK contains only generic `default.json` — no personal
      profile, no keywords, no API key (verify: `unzip -l … | grep assets/`)
- [ ] changing `default.json` in a future release does not erase the imported
      profile (covered by the `imported profile overrides a changed default`
      unit test)

### Distribution

- [ ] Obtainium detects v0.1.1
- [ ] v0.1.1 installs over v0.1.0 without a signature mismatch (same package,
      higher versionCode, same signing certificate)
- [ ] settings/app data survive the update
