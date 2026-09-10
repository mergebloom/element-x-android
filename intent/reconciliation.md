# Integrated downstream reconciliation

Upstream repository: `https://github.com/element-hq/element-x-android.git`.
Pinned upstream cutoff: `c623105da65bedc92b5783cb690add22fe0ba6e2`.
Reviewed pre-feature fork baseline: `ddaa1944597a95499d4240da7bc928da0ab103a0`.
The baseline is context, **not** the inventory cutoff. No upstream refresh is in scope.

## Current authority and supersession

`requirements.md` and `contract.json` integrate EX-001–004 with EC-001, EC-003,
EC-005 and EC-006. `provenance.json` records sanitized approved decision origins,
original document SHA-256 values, preserved baseline reasoning and retired mechanisms.
No private chats, machine paths or internal orchestration identifiers belong here.
The earlier lane-only composer map is explicitly superseded by
`implementation-map.json`; lane Markdown is supporting implementation detail.

- Preserve angle-based reasoning selection and circular UI: left Low, up-left
  Medium, up High, up-right Max; same-ray invariance and ordinary tapping. Existing
  activation/angular thresholds remain tuning, not newly invented product approval.
- Preserve typed reasoning event -> actual composer presenter -> awaited plain
  `/reasoning` command -> unchanged user message. Submission success is not backend
  apply acknowledgement; no claimed atomicity or guaranteed concurrent ordering.
- Preserve upstream editing-first eligibility, Markdown IME, mentions, reply/thread
  state and ordinary send. EC-001/EC-005 explicitly replace the previous assumption
  that attachment/voice semantics would remain unchanged; no reasoning metadata is
  added to media and no selector is overlaid on recording/preview controls.
- EC-001 routes versioned caption leases through picker/preview/navigation and
  attempt-owned media preprocessing. Conflict/retry/remount preserve ownership.
- EC-005 routes separate mic controls through retained recorder/preview and shared
  navigation guard; Send is explicit, not release/cancel/permission side effect.
- EC-003/006 route Home Threads through native directory/state/storage/send observers
  into the exact initial thread timeline. No transient parent read, no notification
  dependence, no optimistic enqueue-as-success, no Last opened-as-read shortcut.
- Preserve package and tracked debug signer. The old reasoning release is a prior
  comparison artifact, never a new candidate identity or permission to replace assets.

## Mapping and source freeze

The committed `implementation-map.json` lists every A/M/D/T endpoint path from
upstream, including old reasoning, both whole lanes, shared merge seams, tests,
configuration, workflows, tools and intent files. Check links are traceability;
none mean tests have passed. Future integration workflow/helper paths are exact
reservations and enter the map only after they actually exist. Unknown paths fail
closed and require a reviewed mapping; there is no catch-all production glob.

Before source freeze run `python3 -B intent/refresh_inventory.py --refresh`, inspect
the map and run `--check`. Final source/spec commits and file hashes are collected
only after the owner commits a reviewed clean tree. The external sealed inventory
must match the map and full Git diff exactly. Do not insert a future SHA or runtime
evidence into the committed spec: that would create self-reference or stale pins.

## Evidence and promotion boundaries

Historical lane failures/runs remain separate from final combined exact-SHA receipts.
Do not relabel them as final evidence, infer execution from source, or replace a
blocked native/runtime check with detached helper tests or rendered HTML.
Actual Compose images are required UI evidence, but are not physical-device images.

The candidate gate is bounded prerelease review readiness: all local production
unit/presenter/Compose suites, integrated APK build/metadata, isolated native thread
fixtures and actual APK install/launch smoke. It is not logged-in whole-feature E2E,
full backend/physical-device/upgrade certification or human authorization to publish.
Final-only gaps remain explicit and `--release` remains stricter. Independent review
and explicit promotion approval still precede a new immutable validation prerelease.
