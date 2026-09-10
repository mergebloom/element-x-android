# EC-003 / EC-006 — maintained Threads outcomes

These implementation details support the integrated `requirements.md` and
`contract.json`; they do not replace EX-001..004 or constitute execution evidence.
The complete downstream map is `implementation-map.json`. Integrated requirements
and approved supersession in `provenance.json` take precedence in any conflict.

## EC-003 — Useful cross-room unread thread navigation

- Expose a labeled Home Threads destination with visible Unread and Recent tabs.
  Preserve tab and scroll state when returning. No experimental settings toggle is
  needed to enter or open a thread.
- Discover joined non-space rooms and every thread-list page, progressively with
  two concurrent room workers. Refresh while the destination is active; stop work
  when unsubscribed. Keep global coverage distinct from each row's read state.
- Classify Read, Unread, or Unknown from explicit public/private threaded and
  applicable unthreaded receipts, with proven native event order. Timestamps,
  notification counts, and implicit-own read markers are not evidence. A latest
  own event does not clear preceding incoming unread activity.
- An unresolved/missing receipt, missing verified current head, undecryptable
  candidate, or exhausted ordering budget remains Unknown. Known Unread is useful
  alongside Unknown rows. Only complete discovery and classification may show
  No unread threads. Offer retry and explicit deeper ordering resolution.
- Directory browsing, filtering, pagination, and Recent lookup do not emit read
  receipts. Opening creates the exact account/room/root thread timeline without
  first composing or exposing an ordinary parent timeline. Only the opened
  timeline's existing read lifecycle may acknowledge reading.
- Missing targets remain unavailable, never silently redirecting to another
  thread or parent room. Any Open room action must be explicit and deliberate.

## EC-006 — Private durable local thread activity

- Recent is explicitly On this device. Last opened and Last messaged are separate
  shortcuts, coalesced on one row only when their root identities match. Opening
  is local viewing, not a server-synchronized read receipt.
- Record an opening only after the exact thread is loaded and displayed. Record
  successful text, voice and attachment sends through the account-wide native
  SentEvent callback and confirmed server event identity, not local enqueue or
  composer-specific optimistic hooks. Failed/cancelled sends do not move activity.
- Persist only identifiers, local order, and retention metadata in session-private
  files. No transcript, room name, preview, caption, attachment name, or credentials
  are stored in the activity files. Resolve presentation through the native client.
- Keep at most 100 roots per account with 90-day inactivity eviction; retain the
  two shortcuts within the same bound. Use durable monotonic activity order to
  tolerate clock rollback and delayed lookup completion. Bound pending confirmed
  IDs and hashed replay tombstones as well. A stuck lookup must not block all later
  confirmations indefinitely; overflow evicts oldest pending confirmations, not
  newer ones, and is logged. This is navigation history, not a delivery ledger.
- Clear is explicit, durable across restart, and cannot resurrect an in-flight
  confirmation. Logout seals writers and awaits the native observer before session
  directory removal so late UI/native callbacks cannot recreate private history.

## Production map and acceptance evidence

- `libraries/matrix/api/.../threads/ThreadDirectory.kt`: shared identity/state contract.
- `libraries/matrix/impl/.../threads/`: native discovery, explicit reducer, bounded
  ordering, lifecycle coordinator, private storage and confirmed-send observer.
- `libraries/matrix/impl/.../RustMatrixClient.kt`: account ownership and cleanup.
- `features/home/{api,impl}`: first-class destination, native-backed route, visible
  states, tabs, retry/clear/unavailable UI and Compose regressions/captures.
- `appnav` and `features/messages/{api,impl}`: typed initial thread navigation,
  exact timeline loader, no transient parent timeline, opened activity lifecycle.
- `libraries/featureflag/api/.../FeatureFlags.kt`: normal-entry default capability.
- Matching Matrix/test and feature/appnav tests: identity, receipt safety,
  persistence, concurrency, replay, navigation and UI regression evidence.
- `.github/workflows/threads-native.yml`, `scripts/threads-native`, and Matrix
  androidTest: pinned Android AAR + disposable server end-to-end native proof.
- `.github/workflows/threads-validation.yml`: exact-SHA canonical JVM/Robolectric
  suites and actual production Compose images, not a golden comparison claim.

Each delivery's external requirement inventory lists every changed path against
the pinned upstream/HEAD, with nondeleted source SHA-256 in the manifest. The feature
baseline is historical context, not the diff cutoff. Actions workflows must bind results
to that same HEAD. Native SQLite/client reopen does not establish full Android
process-death recovery, encryption coverage, physical accessibility, whole-app APK
upgrade, or release approval. Those remain separately reported integration gates.
