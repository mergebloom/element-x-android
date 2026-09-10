# Integrated intent and evidence gate

Python 3.11+ standard library and Git only. No Gradle/install/network calls, and the
validator never executes commands named in evidence. All four Android outcomes
(EC-001 captions, EC-003 cross-room unread, EC-005 safe voice, EC-006 local Recent)
and preserved EX-001–004 reasoning/composer/APK invariants share one contract.

- `requirements.md`: integrated acceptance outcomes and explicit boundaries.
- `contract.json`: schema-2 check registry and separate candidate/final gates.
- `provenance.json`: sanitized decision origins, original document hashes and
  supersession. Private source documents are not bundled or publicly retrievable.
- `implementation-map.json`: complete downstream path -> requirement/check map.
  Supporting files are mapped for traceability, not claimed behavioral proof.
- `composer.md`, `threads-requirements.md`: implementation details subordinate to
  integrated requirements; `composer-implementation.json` is a retired map pointer.
- `reconciliation.md`, `release-policy.md`: retained seams and promotion boundaries.

## Run and freeze

From the repository root:

```sh
python3 -B -m unittest discover -s intent -p 'test_*.py' -v
# Before the release owner commits the reviewed final tree:
python3 -B intent/refresh_inventory.py --refresh
python3 -B intent/refresh_inventory.py --check
# AFTER the owner freezes a clean committed tree (do not put evidence in source):
python3 -B intent/refresh_inventory.py --seal ../candidate-evidence
# Fill a COPY of manifest-template.json as manifest.json with actual receipts.
python3 -B intent/validate_evidence.py manifest.json \
  --source-root . --evidence-root ../candidate-evidence
python3 -B intent/validate_evidence.py manifest.json \
  --source-root . --evidence-root ../candidate-evidence --candidate
python3 -B intent/validate_evidence.py manifest.json \
  --source-root . --evidence-root ../candidate-evidence --release
```

`--check` is a pre-freeze, read-only mapping check; it permits dirty content and
**does not** certify a clean source snapshot. `--refresh` updates only known exact
paths/statuses from the full upstream diff, including additions, deletions, type
changes and nonignored untracked files. Renames are delete/add. Unknown paths fail
closed until a reviewer adds an explicit mapping. There is no catch-all glob.
Exact reserved integration workflow/helper paths activate only when they exist.
Review the refreshed map and commit it together with final implementation.

`--seal` requires clean tracked/index/worktree state, no nonignored untracked files,
correct committed file bytes, and an exact map. It never commits. It writes a new
external `inventory.json` and `manifest-template.json` with real current hashes,
**all checks blocked**, no artifacts and no command claims. It refuses to overwrite
an existing seal. The template is not evidence of execution and cannot pass either
gate. Ignored build outputs are allowed. Any later source or spec commit invalidates
receipts: rerun, do not relabel an earlier lane run with a new SHA.

## Gate meanings

| Mode | Required result | What it does not mean |
| --- | --- | --- |
| No gate flag | Integrity, exact coverage, honest pass/blocked states; no failed layer | Candidate readiness |
| `--candidate` | All unit/presenter/UI/build checks, `native-thread-receipts`, `app-runtime-smoke` positive | Full backend, physical-device, logged-in whole-feature E2E, real-user upgrade, publication approval |
| `--release` | Every registered check in all six layers positive | Production signing, human approval or signed attestation |

Candidate readiness is readiness for independent review of a **validation
prerelease**, not permission to publish. Final-only backend command application,
native encrypted media delivery, physical device, in-place install update and
anonymous distribution/Obtainium checks stay independently blocked until performed.
Every registered check/layer must still be present. Read `candidate_ready`,
`release_qualified`, `candidate_blockers` and `release_blockers` separately.

Exit 1 rejects stale/missing/tampered/dirty/incomplete/failed/mislabeled evidence or
an unmet requested gate. Exit 0 without flags can honestly report both gates false.
The JSON result includes exact source/spec pins, check/requirement/path/artifact
counts and layer statuses. Synthetic temporary Git/log fixtures in validator tests
are removed after execution and are **not Android acceptance receipts**.

## Schema 2 (integrated)

Duplicate JSON keys, IDs and references are rejected. Paths are canonical relative
POSIX paths, without absolute roots, `..`, backslashes, empty segments or escaping
symlinks. Hashes are lowercase SHA-256; commits are full lowercase Git object IDs.
Schema 1 remains readable for historical evidence, but cannot pass `--candidate` or
be substituted for this schema-2 contract. Schema must agree with the committed spec.

### Committed contract and map

The registry fixes exactly EX-001–004 and EC-001/003/005/006. All six layers must have
checks. `upstream_commit` is the verified upstream cutoff, not the later feature
baseline. `implementation_map` names the reviewed committed map. `gates.candidate`
includes every unit/presenter/UI/build check and native-thread/runtime smoke;
`gates.release` includes every registered check. Candidate checks cover every
requirement. Do not weaken the contract to make missing acceptance appear passed.

Each implementation-map `files` row has `path`, `status`, nonempty unique
`requirements` and `checks` lists, and an explanatory `role`. Every mapped requirement
must be covered by a referenced check, and every check must relate to that row.
`reserved_paths` are planned exact-path mappings, not current implementation or
evidence; they are excluded from `files` until present. The external inventory must
match reviewed path/status/requirement/check mappings exactly.

### External inventory

`inventory.json` uses schema 1: `{schema, source_commit, upstream_commit, files}`.
Its `files` must exactly equal the entire upstream-to-HEAD no-renames diff, including
intent, workflow/helper files and deletions. Deletions have mappings, not live hashes.
The original upstream pin must be an ancestor of HEAD and match the integrated spec.

### External manifest

The sealer generates all required arrays and pins. Required fields:

- `schema`: `2`; `source_commit` and `spec_commit`: exact clean HEAD. No future pins.
- `upstream_commit`: exact contract cutoff.
- `spec`: `{path: "intent/contract.json", sha256}` in source root.
- `inventory`: `{path, sha256}` in evidence root.
- `sources`: `{path, sha256}` records for **every tracked intent file and every
  nondeleted downstream file**. Each is checked against working bytes and the
  exact committed blob. Extra tracked source coverage is allowed.
- `artifacts`: unique `{id, path, sha256}` for actual logs, APKs, screenshots,
  metadata and other receipts. Resolved paths must also be unique and stay in
  evidence root; file integrity alone does not establish artifact semantics.
- `commands`: see below; omit commands never attempted, never invent exits/logs.
- `checks`: every registry check exactly once, see below.
- `layers`: all six exactly once, status derived from **all** their checks.

Each command requires:

```json
{
  "id": "unique-actual-command-id",
  "layer": "unit",
  "checks": ["reasoning-angle"],
  "role": "positive",
  "source_commit": "<actual full HEAD>",
  "spec_commit": "<same actual full HEAD>",
  "spec_sha256": "<actual contract SHA-256>",
  "argv": ["<actual executable>", "<actual argument>"],
  "exit_code": 0,
  "expected_exit_code": 0,
  "log": "<actual artifact ID>",
  "markers": ["<nonempty result text actually present in that hashed log>"]
}
```

This fragment has deliberate placeholders, not usable evidence. A command has one
layer and nonempty exact check scope from that layer. Split references into separate
layer-scoped records if one actual combined execution supports multiple layers;
retain shared invocation identity/argv/log, never claim it ran repeatedly. A single
fixture or liveness command cannot semantically establish unrelated acceptance.

`role` is `positive` (actual/expected exit 0), `negative` (actual/expected nonzero)
or `blocked` (actual/expected integer exit and nonempty `reason`). Expected failure
alone never qualifies acceptance. An unattempted check is blocked with an empty
command list; it is not a fictional blocked command.

Each check is `{id, status, commands, reason?}`. `status` is `passed`, `blocked` or
`failed`. Passed requires at least one positive exit-zero command naming that exact
check and layer; negatives may accompany positives. Other statuses require reasons.
Layers are `{id, status, reason?}`: any failed check -> failed; otherwise any blocked
-> blocked; otherwise passed. Failed layers reject the manifest even in no-gate mode.

## Trust and publication boundary

The tool validates supplied integrity/coverage, not truth, authenticity or complete
behavior. It does not parse APK identity, evaluate images, inspect Matrix semantics,
verify human approval or authenticate a CI attestation; named positive commands and
independent reviewers must do those real checks. Logs/hashes/declarations can be
fabricated by an untrusted collector. Do not treat this validator as a security
signature or substitute it for independent review.

Keep runtime evidence outside specs. Before any public push/release, sanitize logs,
command paths, screenshots and provenance without exposing credentials/user content,
private chat transcripts or machine paths. Do not rewrite historical public commits
or prior releases as part of this intent update. Publication, package/signing changes,
user-device operations and live-account messaging require their own authorization.
