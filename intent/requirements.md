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
  silently invent new attachment, voice or concurrent-send semantics.
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

## Evidence boundaries

The independent layers are `unit`, `presenter`, `ui`, `build`,
`external_protocol` and `release`. Unavailable capabilities are explicitly
`blocked` with a reason, never implicitly passed. Negative controls can establish
that a gate rejects bad input; they cannot establish positive product acceptance.
Every declared passed check needs positive exit-zero evidence for that exact
check and layer. All six layers must pass for the validator's bounded
`release_qualified` result; this remains an evidence-completeness statement, not
human approval or cryptographic attestation.
