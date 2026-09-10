# Pinned Android native thread-receipt fixture

**Execution: hosted Android/KVM instrumentation; no local emulator execution is claimed.**
Read the exact-commit Actions artifact (`results.json`, `build.json`, `junit.xml`) for the current verdict.
The local server preflight is wire-infrastructure evidence only. It cannot pass this workflow's native gate.

## Execution

The `Threads native Android proof` workflow builds **only**
`:libraries:matrix:impl:assembleDebugAndroidTest`, then runs that self-instrumenting library APK
in a separate Ubuntu 24.04 / API 33 / x86_64 KVM job, without Gradle in the emulator job.
Both jobs check out and validate the same full commit SHA. No enterprise submodules, credentials,
real Matrix accounts, existing servers, or full application APK are required.

Push the reviewed `feature/threads-directory` branch once; it triggers the lane.
Alternatively dispatch `.github/workflows/threads-native.yml` with `source_sha=<full reviewed SHA>`.
Avoid a duplicate manual dispatch immediately after a push.

```sh
# Fast infrastructure/parser tests (no Android, no server):
python3 -B -m unittest discover -s scripts/threads-native -p 'test_*.py' -v
bash -n scripts/threads-native/run-emulator.sh

# Optional real disposable-server infrastructure preflight, explicitly NOT native:
uv venv /tmp/threads-native-preflight-venv
uv pip install --python /tmp/threads-native-preflight-venv/bin/python --no-cache matrix-synapse==1.160.0
/tmp/threads-native-preflight-venv/bin/python -B scripts/threads-native/smoke_server.py

# Hosted build command (do not run on the constrained shared host):
./gradlew :libraries:matrix:impl:assembleDebugAndroidTest \
  --no-daemon --no-configuration-cache --no-parallel --max-workers=2 \
  -Dorg.gradle.jvmargs='-Xmx3g -XX:ActiveProcessorCount=2' \
  -Pkotlin.daemon.jvmargs='-Xmx2g -XX:ActiveProcessorCount=2'
python3 -B scripts/threads-native/evidence.py package --sha "$EXPECTED_SHA"
# In a fresh hosted emulator with the dedicated Synapse venv:
bash scripts/threads-native/run-emulator.sh
python3 -B scripts/threads-native/evidence.py verify --sha "$EXPECTED_SHA"
```

## Required real-native cases

Exactly these **five** JUnit cases must start and pass; zero tests, missing cases, skips, duplicates,
crashes, wrong source, wrong native artifact, and absent evidence fail closed:

1. `explicitReceiptsAndOwnLatestSurviveNativeSqliteReopen`: real native Client login, union room subscriptions,
   Simplified Sliding Sync ingestion into native SQLite, initially absent explicit receipts, public receipt
   visibility in two native clients, private receipt privacy, ordered thread timeline pagination, incoming
   reply before latest-own, unchanged explicit anchor after own send, same-store/client-device reopen,
   main-scope isolation, same-event threaded/private versus unthreaded/public fallback in a fresh store,
   private unthreaded advancement, and other-room isolation.
2. `nativePaginationAndProductionListAdapterReachOlderRoots`: 12 roots in each of two synthetic rooms;
   actual `Room.threadListService().paginate()` through the existing **production**
   `RustThreadsListService`, reaching an old root absent from page one, mapped IDs, dedupe and end-state.
   The pinned service uses server-default page size; the test does not assume that FFI documentation's
   default is the actual server default (Synapse 1.160.0 uses five when limit is omitted).
3. `receiptOnlyRefreshNewRootAndBrowsingNeverWriteReceipts`: receipt-only store advancement with an unchanged
   native list head; bounded reset/refetch discovers a new root and refreshes an existing head; directory/list
   and thread-order reads in both rooms emit zero receipt/read-marker writes. A deliberate final native
   `Room.sendSingleReceipt` is the **one** required positive-control write.

4. `productionDirectoryResolvesReceiptChangesAcrossRoomsWithoutWriting`: actual new production
   directory with SSS still active, native v2 sync barrier, cross-room identity, missing evidence,
   incoming-before-own Unread, private receipt-only Read, unrelated unthreaded anchor, new incoming
   after that anchor, and zero receipt writes across every scan.
5. `confirmedNativeTextFileAndVoiceSendsPersistRecentButFailureDoesNot`: actual pinned native thread
   sends for text, a file, and a WAV voice message; the production account-wide SentEvent observer
   updates the correct roots in confirmation order. A failed missing-file send leaves Recent unchanged;
   reopening the device-local Recent file preserves the three confirmed records and stores no bodies.

The fixture uses real server-returned IDs and native timeline diff order, never timestamp sorting,
notification counts, implicit-own unread counters, or receipt absence => zero.
All data is synthetic and unencrypted. Seed messages/receipts use the same-run control channel;
observations, pagination, stores, client restore, confirmed sends, production unread adapter,
and receipt-audit calibration use the actual AAR. Library sends are not whole-app composer UI proof.

## Native source and artifact binding

- `org.matrix.rustcomponents:sdk-android:26.09.08` AAR SHA-256:
  `5b3a4c337704b926137c55e1f2d36451bf8a90066a624ce8454752cc5323387b`.
- Rust release source: `0af7a3217d29c0f92b7cd888772357eff0c57186`.
- Actual FFI constructors/method signatures were inspected in this cached AAR's API JAR with `javap`;
  the helper also passed a standalone Kotlin compiler check against that API (not a Gradle/APK/runtime test).
- [`build.rs`](https://github.com/matrix-org/matrix-rust-sdk/blob/0af7a3217d29c0f92b7cd888772357eff0c57186/bindings/matrix-sdk-ffi/build.rs)
  sets `GitclBuilder::default().sha(true)` (short SHA).
  [`sdk_git_sha`](https://github.com/matrix-org/matrix-rust-sdk/blob/0af7a3217d29c0f92b7cd888772357eff0c57186/bindings/matrix-sdk-ffi/src/lib.rs)
  returns `VERGEN_GIT_SHA`. The verified AAR's actual x86_64 ELF contains `0af7a3217`, not the full SHA.
  The fixture therefore asserts the real `sdkGitSha()` result equals `0af7a3217` at runtime;
  the runtime assertion is part of every executed fixture login.
- The fixture calls real `initPlatform` once, with system/file tracing disabled and no Sentry config,
  matching the source's Android JNI/Tokio initialization before building clients.
- APK packaging verifies the original AAR hash and compares x86_64 ELF GNU build ID **plus `.text` and
  `.rodata` SHA-256** with the packaged library. This accepts debug-section stripping only when native
  code/constants and build identity still agree. Both original and packaged whole-library hashes are recorded.
  Locally verified against the actual AAR and a `strip --strip-unneeded` copy: whole bytes **changed**, while
  build ID `2f470653572d827a38873dde4dc82a534b96a8d3` and both section hashes matched.
- `loadUserReceipt` is a sync-fed local-store read. The fixture does not claim it fetches a receipt remotely.
  `setRoomSubscriptions` replaces the set, so the test always supplies its complete room union.

## Evidence and security

Synapse 1.160.0 is installed into a job-only venv. The harness refuses occupied ports, starts its own fresh
SQLite server, binds server and proxy to host loopback (`18948` / `18949`), disables federation/push/previews,
and accepts no user-supplied server URL or accounts. Android reaches only `http://10.0.2.2:18949`.
The cleartext exception and INTERNET permission exist **only in the library androidTest manifest**.

Passwords and tokens remain in fixture process/device memory; server configs/keys/logs/SQLite/media stay in a
new temporary directory removed on termination. Nothing containing credentials is handed off via artifacts,
workflow arguments or repository files. Control/setup traffic goes directly to the fixture server, while
native traffic crosses the auditing proxy. The audit counts receipt/read-marker requests even if redundant
or unsuccessful, records explicit before/after browsing counters, and requires exactly one calibration write.

Only the built test APK + `build.json` are handed between jobs. The evidence artifact allows only
`build.json`, `device.json`, `server.json`, `audit.json`, `results.json`, and sanitized `junit.xml`.
Failure diagnostics retain exception **class names** and Matrix/Element Kotlin/Java **source file/line frames**;
exception messages, native request/response bodies, full streams, tokens and server/app stores are discarded.
No logcat or server log upload is enabled. The parser unit tests include secret-shaped message rejection.

## Integration hook and remaining gates

`NativeThreadFixture.create()` / `login()` / `reopen()` expose genuine `NativeFixtureSession.client`,
`sync`, `room(id)`, `Room.receiptId(...)`, and `Room.orderedThread(...)` in androidTest scope. Parent can wire
additional production adapters to these objects. Both the existing thread-list adapter and the new
RustThreadDirectorySource are directly exercised, along with the production confirmed-send observer.

Separate gates: the Threads production validation workflow runs canonical Matrix/Home/Messages/
TextComposer/Appnav regressions and genuine Compose captures. It is not this native library workflow.
Neither workflow by itself proves a packaged whole-app force-stop/relaunch, encrypted real-account
history, physical-device accessibility, install/update, or production release qualification. Native
SQLite/client reopen is **not** force-stop/relaunch proof. Threads is enabled by default in production;
the fixture also explicitly initializes the native capability for its isolated library Client.
