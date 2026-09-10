"""Synthetic test fixtures only; these are not Android acceptance evidence."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

LAYERS = ("unit", "presenter", "ui", "build", "external_protocol", "release")
SCRIPT = Path(__file__).with_name("validate_evidence.py")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        # All temporary writes remain in the owned intent directory.
        self.temp = tempfile.TemporaryDirectory(prefix=".validator-test-", dir=SCRIPT.parent)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.evidence = self.root / "evidence"
        self.source.mkdir()
        self.evidence.mkdir()
        self.git("init", "-q")
        self.git("config", "user.name", "Test fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        (self.source / "code.txt").write_text("upstream\n")
        (self.source / "deleted.txt").write_text("deleted in fork\n")
        self.git("add", ".")
        self.git("commit", "-qm", "Fixture upstream")
        self.upstream = self.git("rev-parse", "HEAD")
        (self.source / "intent").mkdir()
        self.spec = {
            "schema": 1, "requirements": ["EX-001"], "layers": list(LAYERS),
            "checks": [{"id": layer + "-check", "layer": layer,
                        "requirements": ["EX-001"]} for layer in LAYERS],
        }
        self.write_json(self.source / "intent/contract.json", self.spec)
        (self.source / "code.txt").write_text("fork\n")
        (self.source / "deleted.txt").unlink()
        self.git("add", "-A")
        self.git("commit", "-qm", "Fixture fork")
        self.head = self.git("rev-parse", "HEAD")
        self.inventory = {
            "schema": 1, "source_commit": self.head, "upstream_commit": self.upstream,
            "files": [{"path": path, "status": status, "requirements": ["EX-001"],
                       "checks": ["unit-check"]}
                      for path, status in [("code.txt", "M"), ("deleted.txt", "D"),
                                           ("intent/contract.json", "A")]],
        }
        self.data = {
            "schema": 1, "source_commit": self.head, "upstream_commit": self.upstream,
            "spec": {"path": "intent/contract.json", "sha256": sha(self.source / "intent/contract.json")},
            "inventory": {"path": "inventory.json", "sha256": ""},
            "sources": [{"path": path, "sha256": sha(self.source / path)}
                        for path in ["code.txt", "intent/contract.json"]],
            "artifacts": [], "commands": [], "checks": [], "layers": [],
        }
        self.sync_inventory()
        for layer in LAYERS:
            log = self.evidence / (layer + ".log")
            log.write_text("TEST FIXTURE PASS " + layer + "\n")
            self.data["artifacts"].append({"id": layer + "-log", "path": log.name, "sha256": sha(log)})
            self.data["commands"].append({
                "id": layer + "-command", "layer": layer, "role": "positive", "checks": [layer + "-check"],
                "source_commit": self.head, "spec_sha256": self.data["spec"]["sha256"],
                "argv": ["fixture-check", layer], "exit_code": 0, "expected_exit_code": 0,
                "log": layer + "-log", "markers": ["TEST FIXTURE PASS " + layer],
            })
            self.data["checks"].append({"id": layer + "-check", "status": "passed",
                                        "commands": [layer + "-command"]})
            self.data["layers"].append({"id": layer, "status": "passed"})

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.source), *args], text=True).strip()

    def write_json(self, path, value):
        path.write_text(json.dumps(value), encoding="utf-8")

    def sync_inventory(self):
        self.write_json(self.evidence / "inventory.json", self.inventory)
        self.data["inventory"]["sha256"] = sha(self.evidence / "inventory.json")

    def check(self, release=False, candidate=False):
        self.assertTrue(SCRIPT.is_file(), "validator implementation is missing")
        spec = importlib.util.spec_from_file_location("evidence_validator", SCRIPT)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        options = {"candidate": True} if candidate else {}
        return module.validate(self.data, self.source, self.evidence, release=release, **options)

    def rejected(self, text):
        with self.assertRaisesRegex(ValueError, text):
            self.check()

    def block(self, layer):
        check = next(c for c in self.data["checks"] if c["id"] == layer + "-check")
        check.update(status="blocked", commands=[], reason="Required environment unavailable")
        next(l for l in self.data["layers"] if l["id"] == layer).update(
            status="blocked", reason="Required environment unavailable")
        self.data["commands"] = [c for c in self.data["commands"] if c["layer"] != layer]

    def test_complete_fixture_qualifies(self):
        self.assertTrue(self.check(release=True)["release_qualified"])

    def test_blocked_layers_are_independent_and_not_release(self):
        self.block("external_protocol")
        result = self.check()
        self.assertFalse(result["release_qualified"])
        self.assertEqual(result["layers"]["unit"], "passed")
        self.assertEqual(result["layers"]["external_protocol"], "blocked")
        with self.assertRaisesRegex(ValueError, "release"):
            self.check(release=True)

    def test_all_blocked_is_honest_not_qualification(self):
        for layer in LAYERS:
            self.block(layer)
        self.data["artifacts"] = []
        self.assertFalse(self.check()["release_qualified"])

    def test_negative_only_cannot_pass(self):
        self.data["commands"][0].update(role="negative", exit_code=1, expected_exit_code=1)
        self.rejected("positive")

    def test_blocked_command_cannot_pass(self):
        self.data["commands"][0].update(role="blocked", exit_code=124, expected_exit_code=124, reason="Timeout")
        self.rejected("positive")

    def test_positive_nonzero_rejected(self):
        self.data["commands"][0].update(exit_code=1, expected_exit_code=1)
        self.rejected("positive")

    def test_negative_zero_rejected(self):
        self.data["commands"][0]["role"] = "negative"
        self.rejected("negative")

    def test_negative_control_can_accompany_positive(self):
        command = copy.deepcopy(self.data["commands"][0])
        command.update(id="negative-control", role="negative", exit_code=1, expected_exit_code=1)
        self.data["commands"].append(command)
        self.data["checks"][0]["commands"].append(command["id"])
        self.check()

    def test_missing_role(self):
        del self.data["commands"][0]["role"]
        self.rejected("role")

    def test_stale_head(self):
        self.data["source_commit"] = self.upstream
        self.rejected("HEAD")

    def test_abbreviated_pin(self):
        self.data["source_commit"] = self.head[:8]
        self.rejected("commit")

    def test_stale_command(self):
        self.data["commands"][0]["source_commit"] = self.upstream
        self.rejected("stale command")

    def test_stale_command_spec(self):
        self.data["commands"][0]["spec_sha256"] = "0" * 64
        self.rejected("stale command")

    def test_dirty_source(self):
        (self.source / "code.txt").write_text("dirty")
        self.rejected("dirty")

    def test_untracked_source(self):
        (self.source / "new.txt").write_text("untracked")
        self.rejected("untracked")

    def test_wrong_spec_hash(self):
        self.data["spec"]["sha256"] = "0" * 64
        self.rejected("hash")

    def test_wrong_source_hash(self):
        self.data["sources"][0]["sha256"] = "0" * 64
        self.rejected("hash")

    def test_missing_source_coverage(self):
        self.data["sources"].pop(0)
        self.rejected("source coverage")

    def test_missing_inventory_diff_file(self):
        self.inventory["files"].pop()
        self.sync_inventory()
        self.rejected("inventory.*diff")

    def test_deleted_file_must_be_in_inventory(self):
        self.inventory["files"] = [f for f in self.inventory["files"] if f["status"] != "D"]
        self.sync_inventory()
        self.rejected("inventory.*diff")

    def test_wrong_inventory_status(self):
        self.inventory["files"][0]["status"] = "A"
        self.sync_inventory()
        self.rejected("inventory.*diff")

    def test_inventory_hash(self):
        (self.evidence / "inventory.json").write_text("{}")
        self.rejected("hash")

    def test_inventory_stale(self):
        self.inventory["source_commit"] = self.upstream
        self.sync_inventory()
        self.rejected("inventory.*pin")

    def test_inventory_requires_requirement_mapping(self):
        self.inventory["files"][0]["requirements"] = []
        self.sync_inventory()
        self.rejected("requirements")

    def test_inventory_unknown_check(self):
        self.inventory["files"][0]["checks"] = ["unknown"]
        self.sync_inventory()
        self.rejected("check")

    def test_missing_artifact(self):
        (self.evidence / "unit.log").unlink()
        self.rejected("missing")

    def test_corrupted_artifact(self):
        (self.evidence / "unit.log").write_text("corrupted")
        self.rejected("hash")

    def test_unknown_command_reference(self):
        self.data["checks"][0]["commands"] = ["unknown"]
        self.rejected("command")

    def test_unknown_log_reference(self):
        self.data["commands"][0]["log"] = "unknown"
        self.rejected("log")

    def test_cross_layer_command_rejected(self):
        self.data["checks"][0]["commands"] = ["ui-command"]
        self.rejected("layer")

    def test_no_positive_evidence(self):
        self.data["checks"][0]["commands"] = []
        self.rejected("positive")

    def test_missing_check(self):
        self.data["checks"].pop()
        self.rejected("checks")

    def test_missing_layer(self):
        self.data["layers"].pop()
        self.rejected("layers")

    def test_duplicate_check(self):
        self.data["checks"].append(copy.deepcopy(self.data["checks"][0]))
        self.rejected("duplicate")

    def test_mislabeled_layer(self):
        self.data["layers"][0].update(status="blocked", reason="Not actually blocked")
        self.rejected("layer status")

    def test_blocked_requires_reason(self):
        self.block("ui")
        self.data["checks"][2].pop("reason")
        self.rejected("reason")

    def test_marker_must_be_present(self):
        self.data["commands"][0]["markers"] = ["NOT IN LOG"]
        self.rejected("marker")

    def test_boolean_exit_code_is_not_zero(self):
        self.data["commands"][0]["exit_code"] = False
        self.rejected("exit")

    def test_artifact_path_escape(self):
        self.data["artifacts"][0]["path"] = "../outside"
        self.rejected("path")

    def test_absolute_artifact_path(self):
        self.data["artifacts"][0]["path"] = str(self.evidence / "unit.log")
        self.rejected("path")

    def test_symlink_escape(self):
        (self.evidence / "escape.log").symlink_to(self.source / "code.txt")
        self.data["artifacts"][0].update(path="escape.log", sha256=sha(self.source / "code.txt"))
        self.rejected("path")

    def test_command_scope_cannot_claim_other_check(self):
        self.data["commands"][0]["checks"] = ["ui-check"]
        self.rejected("command check.*layer")

    def test_positive_evidence_must_name_exact_check(self):
        self.spec["checks"].append({"id": "other-unit-check", "layer": "unit", "requirements": ["EX-001"]})
        self.write_json(self.source / "intent/contract.json", self.spec)
        self.git("add", ".")
        self.git("commit", "-qm", "Add independent fixture check")
        self.head = self.git("rev-parse", "HEAD")
        self.data["source_commit"] = self.head
        self.inventory["source_commit"] = self.head
        self.sync_inventory()
        self.data["spec"]["sha256"] = sha(self.source / "intent/contract.json")
        for source in self.data["sources"]:
            source["sha256"] = sha(self.source / source["path"])
        for command in self.data["commands"]:
            command.update(source_commit=self.head, spec_sha256=self.data["spec"]["sha256"])
        self.data["checks"].append({"id": "other-unit-check", "status": "passed", "commands": ["unit-command"]})
        self.rejected("scope")

    def test_command_scope_required(self):
        self.data["commands"][0].pop("checks")
        self.rejected("command checks")

    def test_failed_layer_rejected(self):
        self.data["checks"][0].update(status="failed", reason="Behavior assertion failed")
        self.data["layers"][0].update(status="failed", reason="Behavior assertion failed")
        self.rejected("failed layer")

    def test_duplicate_source_rejected(self):
        self.data["sources"].append(copy.deepcopy(self.data["sources"][0]))
        self.rejected("duplicate")

    def test_empty_marker_rejected(self):
        self.data["commands"][0]["markers"] = [""]
        self.rejected("markers")

    def test_cli_validates_portable_roots(self):
        self.write_json(self.evidence / "manifest.json", self.data)
        proc = subprocess.run([sys.executable, "-B", str(SCRIPT), "manifest.json", "--source-root", "source",
                               "--evidence-root", "evidence", "--release"], cwd=self.root, capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertTrue(json.loads(proc.stdout)["release_qualified"])

    def test_cli_rejects_duplicate_json_keys(self):
        (self.evidence / "manifest.json").write_text('{"schema": 1, "schema": 1}')
        proc = subprocess.run([sys.executable, "-B", str(SCRIPT), "manifest.json", "--source-root", "source",
                               "--evidence-root", "evidence"], cwd=self.root, capture_output=True, text=True)
        self.assertEqual(proc.returncode, 1)
        self.assertIn("duplicate", proc.stdout)


if __name__ == "__main__":
    unittest.main()
