# Voice input mechanisms on Android — research notes

Status: reference. Answers "how do other voice-input tools actually work, and
is keyboard integration the only way?"

## The four mechanisms to get text into an arbitrary app

| # | Mechanism | Who uses it | Above chat heads? | Cost |
|---|-----------|-------------|-------------------|------|
| 1 | **IME** (`InputMethodService`) | Gboard, SwiftKey, Samsung, AI keyboards | yes (IME layer > `TYPE_APPLICATION_OVERLAY`) | be an input method |
| 2 | **AccessibilityService + floating bubble** (`TYPE_ACCESSIBILITY_OVERLAY`) | AIDictation / WritingMate, Google Voice Access | yes (accessibility overlay; no `SYSTEM_ALERT_WINDOW` needed) | accessibility permission |
| 3 | **Activity via `ACTION_RECOGNIZE_SPEECH`** | this project, Google system recognizer | **no** (activity < overlay) | none |
| 4 | **Overlay via `SYSTEM_ALERT_WINDOW`** (no accessibility) | assorted floating-mic apps | yes | "display over other apps" permission |

## Source evidence (fetched 2026)

- **AIDictation (writingmate/aidictation)** — commercial standalone dictation.
  Android implementation (`service/OverlayDictationAccessibilityService.kt`):
  - extends `AccessibilityService`, listens for `TYPE_VIEW_FOCUSED` /
    `TYPE_VIEW_CLICKED` / `TYPE_WINDOW_STATE_CHANGED` to detect editable fields;
  - adds a draggable mic bubble via `windowManager.addView(...)` with
    `LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`;
  - bubble states Idle / Recording / Processing; VAD auto-stop at 1.5 s silence;
    transcription pipeline afterwards (same shape as ours);
  - also ships a "Simple Keyboard" integration (IME) as a second surface.
- **Google Voice Access / accessibility dictation** — accessibility-based,
  text insertion through accessibility actions.
- **Gboard / SwiftKey / Samsung** — voice typing lives inside the IME window.

## Conclusions for this project

1. Keyboard integration is the mainstream but **not** the only way; the
   standalone-dictation industry standard is accessibility + floating bubble.
2. Every above-chat-heads mechanism requires either the accessibility
   permission or the display-over-apps permission — both deliberately out of
   scope for this project.
3. With the current constraints, the `ACTION_RECOGNIZE_SPEECH` Activity is the
   single mechanism; the chat-head limitation is documented (README/TESTING).
4. In-constraint alternative if the bottom-panel UX is ever acceptable to
   trade: an **opaque full-screen** recognition window behaves like the Google
   system recognizer — still below chat heads, but usable around the bubble.
   No new permission; only the panel look changes.
