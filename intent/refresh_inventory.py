#!/usr/bin/env python3
"""Refresh a reviewed exact-path map, or seal real hashes with ALL checks blocked.

Standard library + Git. Never run acceptance commands or invent their receipts.
"""
import argparse
import json
from pathlib import Path
from validate_evidence import commit, digest, git, index, inside, load_json, names, relative, require


def changed(source, upstream):
    raw = git(source, "diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--name-status", "-z", upstream, "--")
    tokens = raw.decode().rstrip("\0").split("\0") if raw else []
    require(len(tokens) % 2 == 0, "unreadable diff")
    result = dict(zip(tokens[1::2], tokens[::2]))
    for path in git(source, "ls-files", "--others", "--exclude-standard", "-z").decode().split("\0"):
        if path:
            result[path] = "A"
    return result


def encode(value):
    return json.dumps(value, indent=2) + "\n"


def run(source, refresh=False, seal=None):
    source = Path(source).resolve()
    require(Path(git(source, "rev-parse", "--show-toplevel").decode().strip()).resolve() == source, "source root must be repository root")
    spec = load_json(source / "intent/contract.json")
    require(spec.get("schema") == 2, "requires integrated schema 2")
    upstream = commit(spec.get("upstream_commit"))
    head = commit(git(source, "rev-parse", "HEAD").decode().strip())
    git(source, "merge-base", "--is-ancestor", upstream, head)
    path = inside(source, spec.get("implementation_map"))
    mapping = load_json(path)
    require(mapping.get("schema") == 2 and mapping.get("upstream_commit") == upstream, "implementation map pin/schema mismatch")
    current = index(mapping.get("files"), "implementation map", "path")
    reserved = index(mapping.get("reserved_paths", []), "reserved paths", "path")
    definitions = index(spec.get("checks"), "checks")
    requirements = names(spec.get("requirements"), "requirements")
    diff = changed(source, upstream)
    missing = sorted(set(diff) - current.keys() - reserved.keys())
    require(not missing, "unmapped paths require explicit review: " + ", ".join(missing))
    rows = []
    for name, status in sorted(diff.items()):
        relative(name)
        row = dict(current[name] if name in current else reserved[name])
        row["status"] = status
        require(status in {"A", "M", "D", "T"}, "unsupported status")
        reqs = names(row.get("requirements"), "mapped requirements")
        checks = names(row.get("checks"), "mapped checks")
        require(reqs <= requirements and checks <= definitions.keys(), "unknown mapping IDs")
        require(reqs <= set().union(*(set(definitions[c]["requirements"]) for c in checks)), "incomplete mapping checks")
        require(all(set(definitions[c]["requirements"]) & reqs for c in checks), "irrelevant mapping check")
        rows.append(row)
    # Order is normalized for deterministic diffs; --check compares semantics.
    require(refresh or {r["path"]: r for r in rows} == current, "implementation map differs from complete downstream diff; run --refresh before freeze")
    if refresh:
        mapping["files"] = rows
        path.write_text(encode(mapping), encoding="utf-8")
    if seal is not None:
        require(not git(source, "diff", "--no-ext-diff", "--no-textconv", "--name-only", "HEAD", "--"), "dirty tracked source")
        require(not git(source, "ls-files", "--others", "--exclude-standard", "-z"), "untracked source")
        output = Path(seal).resolve()
        require(not output.is_relative_to(source), "seal evidence must be outside source repository")
        inventory = {"schema": 1, "source_commit": head, "upstream_commit": upstream, "files": rows}
        tracked = set(filter(None, git(source, "ls-files", "-z").decode().split("\0")))
        paths = {p for p in tracked if p.startswith("intent/")} | {p for p, status in diff.items() if status != "D"}
        sources = []
        for name in sorted(paths):
            require(name in tracked, "untracked source hash")
            data = inside(source, name).read_bytes()
            require(data == git(source, "show", head + ":" + relative(name)), "committed source hash mismatch")
            sources.append({"path": name, "sha256": digest(data)})
        reason = "Not yet collected at this exact source/spec commit; replace only with actual bounded receipts."
        manifest = {"schema": 2, "source_commit": head, "spec_commit": head, "upstream_commit": upstream,
                    "spec": {"path": "intent/contract.json", "sha256": digest((source / "intent/contract.json").read_bytes())},
                    "inventory": {"path": "inventory.json", "sha256": digest(encode(inventory).encode())},
                    "sources": sources, "artifacts": [], "commands": [],
                    "checks": [{"id": c, "status": "blocked", "commands": [], "reason": reason} for c in definitions],
                    "layers": [{"id": layer, "status": "blocked", "reason": reason} for layer in spec["layers"]]}
        output.mkdir(parents=True, exist_ok=True)
        # Never replace an earlier collection or a completed receipt bundle.
        require(not any((output / name).exists() for name in ("inventory.json", "manifest-template.json")), "seal output already exists")
        (output / "inventory.json").write_text(encode(inventory), encoding="utf-8")
        (output / "manifest-template.json").write_text(encode(manifest), encoding="utf-8")
    return {"mapping": "passed", "diff_files": len(rows), "source_commit": head, "sealed": seal is not None,
            "acceptance_claimed": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", default=".")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--refresh", action="store_true", help="Update only known exact-path statuses/list; review before commit")
    group.add_argument("--check", action="store_true", help="Read-only exact mapping check, accepts a pre-freeze dirty tree")
    group.add_argument("--seal", help="Create inventory and all-blocked manifest template outside a clean source tree")
    args = parser.parse_args()
    try:
        print(json.dumps(run(args.source_root, args.refresh, args.seal), sort_keys=True))
        return 0
    except (ValueError, OSError, TypeError, KeyError) as error:
        print(json.dumps({"mapping": "rejected", "error": str(error)}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
