# EC-001 / EC-005 — Composer contract and implementation

This supplement details the integrated `requirements.md` and `contract.json`, not a separate gate or release-qualification claim. EX-001/EX-002 reasoning and EX-003 ordinary send remain required. Approved EC-001/EC-005 bound the new attachment/voice semantics. In any conflict the integrated requirements and approved supersession in `provenance.json` take precedence.

## EC-001: Version-owned text-first captions

- Capture the original room/thread/reply and editor revision before Gallery, Files or Camera opens. Preserve the ordinary draft while picking.
- Nonempty handoffs select one item, with a visible explanation; attachment-first multi-select remains available. Active edits and immediate GIF/sticker/keyboard rich-content sends are outside automatic handoff.
- The existing preview owns editable caption/media. A successful single-item send creates one captioned attachment, never a separate text event. Only an unchanged captured editor revision may be consumed, including across saveable composer disposal/remount. Equal content after a newer edit remains newer.
- Cancel restores edited caption only while the lease still owns the composer; otherwise Keep editing attachment / Discard attachment draft explicitly resolves the conflict without overwriting current text. Unchanged rich cancellation preserves formatting; plain-caption conversion is disclosed.
- Failed/cancelled attempts retain source media, caption and target. Prepared files and thumbnail cleanup are attempt-owned. Retry uses new prepared files, and upload ownership survives preview remount without duplicate submission.

## EC-005: Explicit-preview voice interaction

- Reuse the existing recorder, player and Matrix media sender. Keep a separate accessible microphone with a typed draft. Disable active-edit recording with an explanation.
- Tap starts hands-free after permission; first permission grant requires a fresh activation and shows Ready. Hold uses platform long-press timing; early movement beyond touch slop aborts.
- Held release stops to review, never sends. Slide toward Cancel (left LTR/right RTL) or upward Lock only beyond both touch slop and the visible 72dp marker, with axis dominance at least 1.25. A marker represents a boundary, not a button. First commit latches; locked release is a no-op. Explicit Cancel/Stop controls have 48dp minimum hit areas.
- Recording exposes time/waveform; preview provides play/pause/seek/delete and explicit Send. Reasoning and text-send controls are hidden during recording/preview.
- Pointer interruption, background, audio-focus loss and disposal never send. Stop valid audio to a recoverable preview. Navigation uses a shared guard on view and asynchronous node exits; Keep stays at origin, Discard waits for recorder acknowledgement. Sending blocks destructive navigation and duplicate send. Account/room/thread/reply identity is captured before asynchronous work.
- Native start ownership exists before first buffer, startup failure is visible, and Cancel wins over a later lifecycle stop. Success clears only the submitted recording identity. This release does not require audio draft persistence across process death.

## Provenance and deliberate deviations

Product-approved outcomes were synthesized from Telegram-first research, not copied as pixel coordinates or source code. Telegram Android's record/lock affordances are an interaction reference; release-to-preview is an explicit safety deviation from Telegram release-to-send. Caption revision leases and conflict handling are product requirements, not claims about other clients.

Public reference pins:
- Telegram Android: https://github.com/DrKLO/Telegram/tree/62b56a07ca7e30e39f7fd00a6728d6bbd716ca1c
- Signal Android: https://github.com/signalapp/Signal-Android/tree/d0bba759e87b6d360ac6af0dd064f40a1e6cadb4
- Actual embedded rich editor: https://github.com/element-hq/matrix-rich-text-editor/tree/81735076261fc9c2be2e60a888e66cf5b603ee6f (2.42.1)

`implementation-map.json` is the current complete downstream requirement/check map. `composer-implementation.json` is a retired lane-only map pointer, not an alternative inventory. Regression sources exercise native Compose and production presenters with named synthetic boundaries. Robolectric/host-compiled rich-editor evidence is not emulator, physical microphone, native encrypted Matrix send, receiver playback, process-death, install/update or release qualification. Those independent layers remain integration/release gates. Historical failures and exact frozen-source evidence are retained outside public source; never replace them with a plausible success record.
