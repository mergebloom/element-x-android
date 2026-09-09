# Intent evidence gate

Portable Python 3.11+ and Git; no packages, Gradle invocation, network requests or
command execution by the validator. `requirements.md` is the acceptance contract;
`contract.json` registers its checks and the six independent evidence layers.

## Run

From the source repository:

```sh
python3 -B -m unittest discover -s intent -p 'test_*.py' -v
python3 -B intent/validate_evidence.py manifest.json \
  --source-root . --evidence-root ../evidence
# Fail unless every independent layer passes:
python3 -B intent/validate_evidence.py manifest.json \
  --source-root . --evidence-root ../evidence --release
```

The manifest argument is relative to the evidence root. Roots are CLI inputs, not
embedded in committed files. Exit 0 means integrity and declared coverage passed;
read `release_qualified` separately. Explicit blocked checks are allowed without
`--release`. Missing, stale, failed, falsely passed and negative-only passed claims
exit 1. JSON output states the result. Tests use temporary synthetic Git/evidence
fixtures under this directory and remove them; they do not establish Android
acceptance.

## Collection order

1. Finalize and commit source, requirements, check registry and validator. Do not
   commit runtime evidence into the source snapshot it describes: that creates a
   self-referential commit/hash problem. Keep generated inventory and manifest in
   the evidence root. No generated manifest is shipped here.
2. Record full `git rev-parse HEAD` and the chosen full upstream commit. Upstream
   must be an ancestor of HEAD. Run checks against that exact clean source. Any
   source commit change invalidates the command pins; rerun rather than relabeling
   older evidence. Ignored build outputs are allowed; tracked changes and
   nonignored untracked files are rejected.
3. Inventory **every** endpoint diff path from
   `git diff --name-status --no-renames -z UPSTREAM HEAD --`, including `intent/`,
   workflow changes and deletions. Renames appear as delete/add. Map each path to
   requirements and registered checks, including supporting documentation/tests.
4. Hash all nondeleted diff files and every tracked file in the spec directory
   (`intent/` here). Additional tracked source files may also be hashed. Hash the
   actual APK, logs, screenshots, metadata reports and other evidence artifacts.
5. Record each command's actual argv, exit status, check scope, layer, source/spec
   pins and log markers. Preserve logs without secrets or user content. A positive
   command must exit 0. An intentionally failing negative control must have a
   nonzero expected and actual exit. A blocked attempted command needs an actual
   exit code and a reason; a command that never ran is omitted, not invented.
6. Record all check and layer states. A blocked check may have an empty command
   list but needs a concrete reason. Then run the gate; publish the JSON outcome
   alongside the bounded evidence, not a blanket acceptance claim.

## Schema 1

JSON objects may include explanatory extra fields, but required fields below may
not be omitted. Duplicate JSON keys, list IDs and references are rejected. Paths
use canonical relative POSIX notation: no absolute paths, backslashes, `..`,
empty segments or symlink escape. SHA-256 values are 64 lowercase hex characters;
commit pins are complete lowercase Git object IDs (40 or 64 characters).

### Committed spec: `contract.json`

- `schema`: integer `1`.
- `requirements`: nonempty unique string IDs. This contract fixes EX-001–EX-004.
- `layers`: exactly `unit`, `presenter`, `ui`, `build`, `external_protocol`, `release`.
- `checks`: objects `{id, layer, requirements, description?}`. Each check belongs
  to exactly one layer and covers existing requirement IDs. Every requirement
  and layer must have at least one check. Generic validator logic does not
  hardcode requirement IDs, file names or check names.

### Generated inventory: evidence-root `inventory.json`

```json
{
  "schema": 1,
  "source_commit": "<full HEAD>",
  "upstream_commit": "<full upstream commit>",
  "files": [
    {
      "path": "relative/source/file.kt",
      "status": "M",
      "requirements": ["EX-001"],
      "checks": ["reasoning-angle", "composer-geometry"]
    }
  ]
}
```

`files` must exactly equal the full Git diff, with no omissions or extras. Status
is `A`, `M`, `D` or `T`; deletion paths need mappings but no current source hash.
Each mapping has nonempty unique requirement/check lists. Every mapped requirement
must be covered by a referenced check; each check must relate to a mapped
requirement. Mapping supporting files is traceability, not proof of behavior.

### Generated manifest: evidence-root `manifest.json`

All arrays below are required. Angle-bracket values in this fragment are schema
placeholders, **not usable evidence**. Expand the arrays to cover the full spec,
all required source paths, every layer and all actual artifacts/commands.

```json
{
  "schema": 1,
  "source_commit": "<full HEAD>",
  "upstream_commit": "<full upstream commit>",
  "spec": {"path": "intent/contract.json", "sha256": "<spec SHA-256>"},
  "inventory": {"path": "inventory.json", "sha256": "<inventory SHA-256>"},
  "sources": [
    {"path": "intent/contract.json", "sha256": "<source SHA-256>"}
  ],
  "artifacts": [
    {"id": "unit-log", "path": "logs/unit.log", "sha256": "<log SHA-256>"}
  ],
  "commands": [
    {
      "id": "angle-tests",
      "layer": "unit",
      "checks": ["reasoning-angle"],
      "role": "positive",
      "source_commit": "<full HEAD>",
      "spec_sha256": "<spec SHA-256>",
      "argv": ["<actual executable>", "<actual argument>"],
      "exit_code": 0,
      "expected_exit_code": 0,
      "log": "unit-log",
      "markers": ["<nonempty actual result text>"]
    }
  ],
  "checks": [
    {"id": "reasoning-angle", "status": "passed", "commands": ["angle-tests"]},
    {
      "id": "backend-command-application",
      "status": "blocked",
      "commands": [],
      "reason": "Authorized backend test environment unavailable"
    }
  ],
  "layers": [
    {"id": "unit", "status": "passed"},
    {"id": "external_protocol", "status": "blocked", "reason": "Backend check blocked"}
  ]
}
```

- `spec` and `sources` resolve within source root; `inventory` and `artifacts`
  resolve within evidence root. Every source hash is checked against both working
  tree bytes and the exact committed Git blob. Spec hash must agree in both places.
- Artifact IDs and resolved paths are unique. A command's `log` references an
  artifact ID, and all nonempty `markers` must occur in that hashed log. Add APKs
  and other nonlog artifacts to the same list; attach explanatory metadata in
  additional fields as needed.
- Every command requires role `positive`, `negative` or `blocked`, matching actual
  and expected integer exit codes, nonempty argv and nonempty check scope from one
  layer. A blocked command also requires `reason`. Negative tests and blocked
  attempts never count toward a passed check.
- Every registered check appears exactly once with `status` `passed`, `blocked`
  or `failed`, plus unique `commands` IDs. Those commands must name that check in
  their scope and belong to its layer. `passed` requires at least one positive
  exit-zero command. `blocked` and `failed` require nonempty reasons.
- Every layer appears exactly once. Derive its status from **all** its checks:
  any failed → failed; otherwise any blocked → blocked; otherwise passed. Nonpass
  layers require reasons. A failed layer rejects the manifest. One layer's green
  tests cannot qualify another layer.

## Trust boundary

This validates consistency of supplied evidence, not authenticity of whoever
collected it. Someone able to rewrite logs, hashes and declarations can fabricate
claims; signed CI attestations are outside this small tool. It does not parse APK
metadata, verify visual geometry or interpret backend traces itself: positive
commands for those registered checks must do the real checks and retain their
artifacts. In particular, hashing an APK proves neither its identity continuity
nor its installability. Passing these validator unit tests proves the evidence
gate, not the Android client. `release_qualified: true` means all declared layers
have bounded positive evidence, not authorization to distribute a release.
