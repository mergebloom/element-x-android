# Review-only Android update channel plan

No release is published by the build workflow. Artifacts use the existing tracked
Android Debug certificate and `io.element.android.x.debug`; this is not an
exclusive production signing key. Never silently replace it or the application ID.

For this integrated build:

- Candidate immutable tag: `reasoning-26.09.1-r1-debug`.
- Primary filename: `elementx-reasoning-26.09.1-r1-arm64-v8a-debug.apk`.
- Optional fallback: `elementx-reasoning-26.09.1-r1-universal-debug.apk`.
- Minimum base version code: `20260902`. Upstream ABI suffixes produce `202609022`
  for arm64 and `202609020` for universal; both exceed previous arm64 `202608002`.
- Version name: upstream `26.09.1` plus `-reasoning.1`.
- Publish a checksummed `release-manifest.json` binding full Git commit, upstream
  cutoff, APK filename/hash/size, package, certificate SHA-256, ABI, versions,
  build type, source revision embedded in the APK and exact verification status.
- Tag exactly the reviewed build commit. Do not reassign a tag or replace an asset.
  Corrections need a new tag, filename and greater embedded versionCode.
- Do not add assets to the obsolete `hermes-directional-v1` release.

Obtainium configuration after explicit release approval:

- Source: `https://github.com/mergebloom/element-x-android`.
- Select tags matching `^reasoning-.*-debug$`.
- Select APK assets matching `^elementx-reasoning-.*-arm64-v8a-debug\.apk$`.
- Do not select the universal and arm64 APK simultaneously or infer source revision
  from a release label. Verify one matching APK per tag and monotonically increasing
  versionCode. Universal is a manual fallback, not a second primary asset.

Review gates still include an authorized installation/update and separate backend
room/thread reasoning canary. Local Compose and presenter tests do not establish
those layers. No user-device/account operations or Matrix messages are performed by
this workflow. The local evidence manifest states actual, not planned, outcomes.
