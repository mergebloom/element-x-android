# Maintained intent

Stable IDs describe outcomes, not a frozen implementation. These are maintenance
acceptance criteria, not evidence that the current build satisfies them. The
machine-readable check registry is `contract.json`; runtime evidence is separate.

## EX-001 — Angle-based reasoning selection with a clear circular UI

- A deliberate swipe from Send chooses reasoning effort by **angle**, not distance.
  Once activated, scaling a drag along the same ray preserves the selection.
- The maintained directional mapping is left → Low, up-left → Medium, up → High,
  up-right → Max. Exact activation distances and angular boundaries are tuning
  choices, not independently approved product requirements.
- Circular sections show the selected effort. Labels and interactive regions must
  not overlap one another or obscure essential composer controls at supported
  display sizes, font scales and layout directions.
- Cancellation, short drags and movement outside the selectable region must not
  send a selected-reasoning message. A valid release sends once. Ordinary tapping
  retains its original meaning, and accessible actions expose the choices.
- Unit geometry checks cannot establish visual fit, gesture consumption or
  accessibility. Those need separate UI evidence.

## EX-002 — Client command submission precedes the unchanged message

- For a selected effort, submit plain `/reasoning low`, `/reasoning medium`,
  `/reasoning high` or `/reasoning max` before invoking the user's message send.
  The command has no user-body formatting or mentions.
- Await the **client command submission result**. While it is suspended, the user
  send callback does not run. On command failure, propagate failure without
  invoking that callback. On success, invoke the callback exactly once.
- Preserve the user's text, HTML, mentions and reply association through the
  existing send path. Propagate the user's send result. With no selected effort,
  invoke the ordinary send exactly once and submit no command.
- This is **not backend processing acknowledgement**, atomic two-event delivery,
  or proof that the requested effort applied. Backend command acceptance, model
  support, room/thread routing, persistence, concurrency and actual processing
  order require independent authorized external-protocol evidence.

## EX-003 — Preserve ordinary composer behavior and upstream improvements

- Normal tap continues the existing ordinary send event and sends one unchanged
  message, without a reasoning command or hidden metadata.
- Preserve upstream composer fixes and improvements, including send eligibility,
  keyboard behavior, mentions, reply association, editing, voice and attachments.
  Adapt the extension at the current integration seams rather than restoring an
  older upstream composer wholesale.
- Selected reasoning must not leak into editing or unrelated routes. Do not
  silently invent new attachment, voice or concurrent-send semantics. EC-001 and
  EC-005 now explicitly approve the bounded caption/voice changes below; they do
  not authorize reasoning on voice/media or changes to normal tap/edit semantics.
- Presenter routing, draft/error behavior and UI interaction need their own
  regression evidence; helper-only tests do not establish those outcomes.

## EX-004 — Stable, monotonically updatable debug APK identity

- Produce a deliberately selected installable debug APK with an exact source
  commit, recorded SHA-256, package identity, signing-certificate fingerprint,
  version name/code and build-command result.
- Preserve the established package ID and signing identity across updates.
  Version codes must increase relative to the previously delivered APK; a renamed
  file or changing version name alone does not establish monotonic updates.
- Verify APK metadata and signature from the actual artifact, not only Gradle
  configuration. Keep the debug signing identity stable across build machines;
  an automatically regenerated debug key is not continuity evidence.
- Verify installation and an in-place update from the previous artifact, with
  preserved app data, before claiming release qualification. A successful build
  does not prove installability, update continuity or distribution availability.
- No automatic publication, package-ID change or signing-key rotation is authorized
  by this contract.

## EC-001 — Version-owned text-first attachment captions

- Capture normal/reply text and immutable editor revision plus account/room/thread/
  reply identity **before** Gallery, Files or Camera. Keep the ordinary draft while
  picking. Active edit is excluded. A newer revision with equal text is still newer.
- Single image/video/document preview receives the draft, permits editing, and
  sends one captioned attachment, never a separate text event. Filename is not the
  caption. Blank, multiline, Unicode and rich-text conversion behavior is explicit.
- Nonempty handoff may restrict selection to one item with visible explanation;
  attachment-first multi-select remains unchanged. Immediate sticker/GIF/keyboard
  rich-content routes are outside automatic handoff and preserve typed text.
- Success consumes only the captured revision. Cancel/Back restores preview edits
  only if ownership remains; conflict offers Keep editing attachment / Discard
  attachment draft without overwriting current text. Rich-content conversion is
  disclosed; unchanged cancellation restores original formatting.
- Failure retains recoverable media/caption/target; retry and remount cannot double
  submit. Async navigation cannot retarget a draft. Prepared-file cleanup belongs
  to the attempt, not the user's retained source media. See `composer.md`.

## EC-003 — Accessible cross-room Unread and Recent destination

- One labeled Home Threads destination is reachable without developer flags even
  with no known threads, loading, offline or error. Visible Unread/Recent tabs have
  selected semantics and retain selection/scroll on Back; rows show room context
  and root/preview without exposing raw IDs as the primary label.
- Discover all accessible current-account joined non-space rooms and all pages
  progressively with bounded work, refresh/new events/restart, retry and per-room
  error isolation. No arbitrary hidden room cap or eager unbounded timeline creation.
- Classify Read/Unread/Unknown from native public/private/threaded and applicable
  unthreaded receipts and verified event order. Test no/unresolved/competing receipt,
  own outgoing events, decryption gaps, pagination and account switch. Never infer
  read state from timestamps, reply totals, room counts or retained notifications.
- Coverage and classification are independent. Unknown/partial remains visible
  and actionable; No unread threads requires complete discovery/classification.
- Browsing/filtering/Recent lookup/pagination never emits receipts. Opening selects
  the exact account/room/root without transient parent-timeline read. Opening B
  cannot mark C read. Missing/inaccessible targets show Thread unavailable; Open
  room is deliberate, never a silent redirect. See `threads-requirements.md`.

## EC-005 — Low-friction voice with explicit review and Send

- Reuse native recorder/player/media send. Separate accessible mic remains usable
  with typed text; preserve text, reasoning selector and ordinary edit/reply/IME.
  Active edit may disable recording with an explanation. No global mic gestures.
- Tap is hands-free after permission is already granted; first grant shows Ready
  and requires fresh activation. Hold releases to preview, not send. Upward Lock
  and toward-Cancel slide are discoverable, touch-slop aware and direction-aware.
  First committed axis latches; ambiguous diagonal stays recording; locked release
  is a no-op; long-press suppresses trailing click. Axis dominance >= 1.25 is the
  approved tuning starting point; the lane's 72dp threshold is implementation tuning.
- Timer/waveform plus explicit Cancel/Stop to review; preview play/pause/seek/delete/
  Send. Accessible tap alternatives and 48dp targets; no overlapping reasoning UI.
- Permission denied/permanently denied, startup error, pointer cancellation, Back,
  background, focus loss and interruption never send. Retain valid audio as preview;
  Keep/Discard guards destructive navigation and Keep stays on original target.
- Record/send account/room/thread/reply before asynchronous work; no retargeting,
  duplicate send or loss on retryable failure. Native duration/waveform/MIME,
  encryption and receiver playback are independent evidence boundaries. Audio draft
  persistence across process death is not required for this first scope; disclose
  lifecycle limitations. Calls, transcription and trimming are out of scope.

## EC-006 — Durable device-local recent thread activity

- Recent says On this device. Separate Last messaged and Last opened shortcuts are
  both shown when different; coalesce labels only for identical account/room/root.
  Last opened means actual viewing here, never synchronized server read state.
- Record successful text/voice/attachment sends from confirmed native sent events,
  not optimistic enqueue; failure/cancel never advances activity. Record opening
  only after actual matching timeline display, never directory rendering.
- Account-isolated durable order survives restart/clock rollback/rapid switches/
  duplicate identifiers and async completion. At most 100 roots per account with
  90-day inactivity eviction; preserve shortcuts within the bound. Bound pending
  confirmations/replay history, too. No transcript/name/preview/caption persistence.
- Clear history and logout are durable and cannot resurrect late in-flight writes.
  Removed/inaccessible targets are unavailable, never silently retargeted. Native
  reopen and storage tests do not imply Android process-death/upgrade qualification.

## Shared UX, provenance and evidence boundaries

Native Element/Material styling, Telegram-first interaction concepts without copied
branding, one-hand access, screen-reader labels, meaningful selected states, 48dp
targets, large font, light/dark, keyboard/IME and predictable Back/cancel are required.
Review actual production Compose screenshots and gesture/navigation tests, including
empty/loading/error/partial/populated threads, caption conflict and recording/preview.

`provenance.json` records sanitized source identities, exact original document hashes
and explicit supersession; it contains no private message transcript or machine path.
`implementation-map.json` covers the complete downstream diff from the upstream pin,
not just the feature baseline or two lanes. Mappings are traceability, not proof.

The six independent layers remain `unit`, `presenter`, `ui`, `build`,
`external_protocol`, `release`. All registered checks must be declared passed,
failed or blocked with honest scope. Positive exit-zero receipts for the exact
check/source/spec are mandatory for every pass. Negative/blocked commands cannot
qualify a check. Integrity validation never interprets product behavior itself.

`--candidate` requires the integrated unit/presenter/Compose/build checks plus native
thread proof and isolated whole-APK install/launch smoke. It means ready for independent
promotion review of a **validation prerelease**, not approval or full certification.
Backend reasoning application, native encrypted media delivery, physical-device
qualification, prior-to-current in-place update and distribution verification are
separate final-release checks; missing evidence remains visibly blocked.

`--release` requires every check in every layer, including final-only checks. Neither
gate changes the independent human promotion requirement. Do not publish before that
approval, modify prior assets, change package/signer, claim production signing, or
label fixture/Compose/emulator evidence as physical-device or full backend proof.
