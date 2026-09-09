#!/usr/bin/env python3
"""Validate evidence integrity and declared coverage, not truth or release approval.

Python standard library + Git only. No evidence commands are executed.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess

LAYERS = ("unit", "presenter", "ui", "build", "external_protocol", "release")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def text(value, label):
    require(isinstance(value, str) and bool(value.strip()), "missing/invalid " + label)
    return value


def names(value, label, nonempty=True):
    require(isinstance(value, list), "invalid " + label)
    for item in value:
        text(item, label)
    require(len(set(value)) == len(value), "duplicate " + label)
    require(bool(value) or not nonempty, "missing " + label)
    return set(value)


def index(value, label, key="id"):
    require(isinstance(value, list), "missing/invalid " + label)
    result = {}
    for item in value:
        require(isinstance(item, dict), "invalid " + label + " entry")
        name = text(item.get(key), label + " " + key)
        require(name not in result, "duplicate " + label)
        result[name] = item
    return result


def relative(value):
    text(value, "path")
    path = PurePosixPath(value)
    require(not path.is_absolute() and value == path.as_posix() and value != "."
            and ".." not in path.parts and "\\" not in value and ":" not in value
            and not any(ord(c) < 32 for c in value), "unsafe path")
    return value


def inside(root, value):
    path = (root / relative(value)).resolve()
    require(path.is_relative_to(root), "path escaped root")
    return path


def digest(data):
    return hashlib.sha256(data).hexdigest()


def hashed_file(root, record, label):
    require(isinstance(record, dict), "invalid " + label)
    expected = record.get("sha256")
    require(isinstance(expected, str) and re.fullmatch(r"[0-9a-f]{64}", expected), "invalid " + label + " hash")
    path = inside(root, record.get("path"))
    require(path.is_file(), "missing " + label)
    # Streaming permits APK-sized artifacts without loading them into memory.
    with path.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    require(actual == expected, label + " hash mismatch")
    return path


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON key: " + key)
        result[key] = value
    return result


def load_json(path):
    with path.open(encoding="utf-8") as stream:
        return json.load(stream, object_pairs_hook=unique_object)


def git(root, *args):
    process = subprocess.run(["git", "-C", str(root), *args], capture_output=True)
    require(process.returncode == 0, "Git source/pin lookup failed")
    return process.stdout


def commit(value) -> str:
    require(isinstance(value, str) and re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", value),
            "invalid full commit pin")
    return value


def validate(data, source_root, evidence_root, release=False):
    """Return a report or raise ValueError. Roots need not share a parent."""
    try:
        return _validate(data, Path(source_root).resolve(), Path(evidence_root).resolve(), release)
    except (KeyError, TypeError, OSError, UnicodeError, subprocess.SubprocessError) as error:
        raise ValueError("malformed or unreadable evidence: " + str(error)) from error


def _validate(data, source, evidence, release):
    require(isinstance(data, dict) and type(data.get("schema")) is int and data["schema"] == 1,
            "unsupported manifest schema")
    head = commit(data.get("source_commit"))
    upstream = commit(data.get("upstream_commit"))
    require(Path(git(source, "rev-parse", "--show-toplevel").decode().strip()).resolve() == source,
            "source root must be the Git repository root")
    require(git(source, "rev-parse", "HEAD").decode().strip() == head, "HEAD differs from evidence")
    require(git(source, "rev-parse", upstream + "^{commit}").decode().strip() == upstream,
            "upstream commit differs from pin")
    git(source, "merge-base", "--is-ancestor", upstream, head)
    require(not git(source, "diff", "--no-ext-diff", "--no-textconv", "--name-only", "HEAD", "--"),
            "dirty tracked source")
    require(not git(source, "ls-files", "--others", "--exclude-standard", "-z"), "untracked source")
    tracked = set(git(source, "ls-files", "-z").decode().rstrip("\0").split("\0"))

    spec_record = data["spec"]
    spec_path = hashed_file(source, spec_record, "spec")
    spec = load_json(spec_path)
    require(isinstance(spec, dict) and type(spec.get("schema")) is int and spec["schema"] == 1,
            "unsupported spec schema")
    requirements = names(spec.get("requirements"), "spec requirements")
    require(names(spec.get("layers"), "spec layers") == set(LAYERS), "spec layers must be the six independent layers")
    definitions = index(spec.get("checks"), "spec checks")
    require(bool(definitions), "missing spec checks")
    for check in definitions.values():
        require(check.get("layer") in LAYERS, "unknown check layer")
        require(names(check.get("requirements"), "check requirements") <= requirements, "unknown check requirements")
    require({c["layer"] for c in definitions.values()} == set(LAYERS), "missing checks for layers")
    require(set().union(*(set(c["requirements"]) for c in definitions.values())) == requirements,
            "missing checks for requirements")

    inventory = load_json(hashed_file(evidence, data.get("inventory"), "inventory"))
    require(isinstance(inventory, dict) and type(inventory.get("schema")) is int and inventory["schema"] == 1,
            "unsupported inventory schema")
    require(inventory.get("source_commit") == head and inventory.get("upstream_commit") == upstream,
            "inventory stale pin")
    files = index(inventory.get("files"), "inventory files", "path")
    raw = git(source, "diff", "--no-ext-diff", "--no-textconv", "--name-status", "--no-renames", "-z", upstream, head, "--")
    tokens = raw.decode().rstrip("\0").split("\0") if raw else []
    require(len(tokens) % 2 == 0, "unreadable Git diff")
    diff = dict(zip(tokens[1::2], tokens[::2]))
    require({p: row.get("status") for p, row in files.items()} == diff, "inventory does not exactly match upstream diff")
    for path, row in files.items():
        relative(path)
        require(row["status"] in {"A", "M", "D", "T"}, "unsupported diff status")
        mapped = names(row.get("requirements"), "inventory requirements")
        refs = names(row.get("checks"), "inventory checks")
        require(mapped <= requirements, "unknown inventory requirements")
        require(refs <= definitions.keys(), "unknown inventory check")
        covered = set().union(*(set(definitions[ref]["requirements"]) for ref in refs))
        require(mapped <= covered, "inventory requirements lack check mapping")
        require(all(set(definitions[ref]["requirements"]) & mapped for ref in refs), "irrelevant inventory check")

    sources = index(data.get("sources"), "sources", "path")
    spec_dir = PurePosixPath(spec_record["path"]).parent
    required_sources = {p for p in tracked if PurePosixPath(p).is_relative_to(spec_dir)}
    required_sources.update(p for p, status in diff.items() if status != "D")
    require(spec_record["path"] in sources and required_sources <= sources.keys(), "missing source coverage")
    for path, record in sources.items():
        require(path in tracked, "source not tracked at HEAD")
        hashed_file(source, record, "source")
        require(digest(git(source, "show", head + ":" + relative(path))) == record["sha256"], "committed source hash mismatch")
    require(sources[spec_record["path"]]["sha256"] == spec_record["sha256"], "source/spec hash mismatch")

    artifacts = index(data.get("artifacts"), "artifacts")
    artifact_paths = set()
    for artifact in artifacts.values():
        path = hashed_file(evidence, artifact, "artifact")
        require(path not in artifact_paths, "duplicate artifact path")
        artifact_paths.add(path)

    commands = index(data.get("commands"), "commands")
    for command in commands.values():
        require(command.get("layer") in LAYERS, "unknown command layer")
        scope = names(command.get("checks"), "command checks")
        require(scope <= definitions.keys(), "unknown command check")
        require(all(definitions[ref]["layer"] == command["layer"] for ref in scope),
                "command check scope crosses layer")
        require(command.get("source_commit") == head and command.get("spec_sha256") == spec_record["sha256"],
                "stale command source/spec")
        role = command.get("role")
        require(role in {"positive", "negative", "blocked"}, "missing/invalid command role")
        argv = command.get("argv")
        require(isinstance(argv, list) and bool(argv), "missing command argv")
        for arg in argv:
            text(arg, "command argument")
        code, expected = command.get("exit_code"), command.get("expected_exit_code")
        require(type(code) is int and type(expected) is int, "invalid command exit code")
        require(code == expected, "unexpected command exit code")
        if role == "positive":
            require(code == 0, "positive command did not exit zero")
        elif role == "negative":
            require(code != 0, "negative control did not fail")
        else:
            text(command.get("reason"), "blocked command reason")
        log = command.get("log")
        require(isinstance(log, str) and log in artifacts, "unknown command log artifact")
        markers = names(command.get("markers"), "command markers")
        content = inside(evidence, artifacts[log]["path"]).read_text(encoding="utf-8", errors="replace")
        require(all(marker in content for marker in markers), "command result marker missing")

    checks = index(data.get("checks"), "checks")
    require(checks.keys() == definitions.keys(), "missing/unknown checks")
    for name, check in checks.items():
        status = check.get("status")
        require(status in {"passed", "blocked", "failed"}, "invalid check status")
        refs = names(check.get("commands"), "check commands", nonempty=False)
        require(refs <= commands.keys(), "unknown check command")
        require(all(commands[ref]["layer"] == definitions[name]["layer"] for ref in refs), "cross-layer command reference")
        require(all(name in commands[ref]["checks"] for ref in refs), "command scope does not name check")
        if status == "passed":
            require(any(commands[ref]["role"] == "positive" and commands[ref]["exit_code"] == 0 for ref in refs),
                    "passed check lacks positive exit-zero evidence")
        else:
            text(check.get("reason"), "check reason")

    layers = index(data.get("layers"), "layers")
    require(layers.keys() == set(LAYERS), "missing/unknown layers")
    statuses = {}
    for layer in LAYERS:
        states = {checks[name]["status"] for name, check in definitions.items() if check["layer"] == layer}
        derived = "failed" if "failed" in states else "blocked" if "blocked" in states else "passed"
        require(layers[layer].get("status") == derived, "layer status differs from its checks")
        if derived != "passed":
            text(layers[layer].get("reason"), "layer reason")
        statuses[layer] = derived
    require("failed" not in statuses.values(), "failed layer evidence")
    qualified = all(status == "passed" for status in statuses.values())
    require(not release or qualified, "release gate: incomplete independent layers")
    return {"integrity": "passed", "release_qualified": qualified, "source_commit": head,
            "layers": statuses, "requirements": len(requirements), "checks": len(checks),
            "diff_files": len(files), "artifacts": len(artifacts)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", help="JSON path relative to evidence root")
    parser.add_argument("--source-root", required=True, help="Git repository root")
    parser.add_argument("--evidence-root", required=True, help="Runtime evidence directory")
    parser.add_argument("--release", action="store_true", help="Require all six layers to pass")
    args = parser.parse_args()
    try:
        evidence = Path(args.evidence_root).resolve()
        result = validate(load_json(inside(evidence, args.manifest)), args.source_root, evidence, release=args.release)
        print(json.dumps(result, sort_keys=True))
        return 0
    except (ValueError, OSError, TypeError) as error:
        print(json.dumps({"integrity": "rejected", "release_qualified": False, "error": str(error)}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
