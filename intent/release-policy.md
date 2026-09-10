# Integrated validation-prerelease policy

This contract and its validators never publish. Preserve `io.element.android.x.debug`
and the established tracked Android Debug signing certificate. That shared debug
identity supports a validation prerelease, not an exclusive production signing key.

`reasoning-26.09.1-r1-debug` is the **previous** delivered release used for comparison,
not the candidate tag. Do not reuse it, replace its assets or make the new prerelease
Latest. The release owner selects a new immutable tag and APK filenames after review;
this document deliberately does not invent a future commit, version or hash.

## Candidate review gate

`validate_evidence.py --candidate` checks exact frozen source/spec and positive
registered candidate checks. Build and inspect the actual APK: package, versionName,
strictly greater versionCode versus previous same-ABI APK, ARM64 contents, embedded
revision, apksigner certificate, size and SHA-256. Source configuration alone is not
metadata/signature evidence. Preserve the previous APK comparison receipt.

Collect combined regressions and production Compose UI images at that same commit,
isolated native Matrix thread fixtures and actual APK emulator install/launch smoke.
An explicit blocked backend/media/physical-device/in-place-upgrade layer does not
become passed merely because the candidate gate permits independent prerelease review.
Read `candidate_ready`, `release_qualified` and both blocker lists separately.

## Independent promotion and delivery

After independent evidence/UX review and explicit promotion approval only:

- Create a new immutable source-bound validation prerelease, explicitly prerelease
  and not Latest. Keep all prior tags/assets intact; no automatic branch promotion.
- Prefer one ARM64 APK for Obtainium; universal is a manual fallback if practical.
- Publish a manifest binding full source/spec commits, upstream pin, file hashes,
  APK name/hash/size, package/cert/ABI/versions, embedded revision, build command,
  exact check outcomes and explicit untested boundaries. Never claim production signing.
- Verify anonymous download bytes equal the inspected APK; remote API success is
  not distribution evidence. Read back release/tag/asset identity after publication.
- Obtainium source is `https://github.com/mergebloom/element-x-android`. Determine
  actual tag/asset filters from the new immutable names, not obsolete placeholders.
  Enable prereleases, select exactly one ARM64 APK, and decode/verify the import
  configuration against the real release API and package ID. Universal must not
  match the primary filter. A link string alone is not an update-path test.

## All-layer final-release gate

`--release` requires every registered check, including authorized backend reasoning
application, native encrypted media delivery/receiver playback, physical-device UX
and microphone checks, actual prior-to-current in-place update with data retained,
and anonymous distribution/Obtainium verification. It is distinct from candidate
review readiness and from human approval. Report unavailable capabilities as blocked;
never relabel Compose/native fixtures/emulator smoke as full real-user certification.

No live user-account messages, installed-user-device changes, package/key rotation,
repository visibility change, force-push or replacement of old release assets is
implied by a passing validator. Test accounts must be isolated disposable fixtures.
