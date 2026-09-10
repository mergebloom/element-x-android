"""Synthetic Git/log fixtures test the gate, never Android acceptance."""
import copy
import json
import unittest
import test_validate_evidence as legacy
from test_validate_evidence import SCRIPT, sha


class IntegratedEvidenceTests(unittest.TestCase):
    git = legacy.EvidenceTests.git
    write_json = legacy.EvidenceTests.write_json
    sync_inventory = legacy.EvidenceTests.sync_inventory
    check = legacy.EvidenceTests.check
    rejected = legacy.EvidenceTests.rejected

    def setUp(self):
        legacy.EvidenceTests.setUp(self)
        self.spec = json.loads(SCRIPT.with_name("contract.json").read_text())
        self.spec["schema"] = 2
        self.spec["upstream_commit"] = self.upstream
        self.spec["implementation_map"] = "intent/implementation-map.json"
        self.data.update(schema=2, artifacts=[], commands=[], checks=[], layers=[])
        self.write_json(self.source / "intent/contract.json", self.spec)
        self.inventory["files"].append({"path": "intent/implementation-map.json", "status": "A"})
        for row in self.inventory["files"]:
            row.update(requirements=["EX-004"], checks=["debug-apk"])
        self.mapping = {"schema": 2, "upstream_commit": self.upstream, "files": copy.deepcopy(self.inventory["files"])}
        self.reseal()
        for definition in self.spec["checks"]:
            name, layer = definition["id"], definition["layer"]
            log = self.evidence / (name + ".log")
            log.write_text("SYNTHETIC FIXTURE PASS " + name)
            self.data["artifacts"].append({"id": name + "-log", "path": log.name, "sha256": sha(log)})
            self.data["commands"].append({
                "id": name, "layer": layer, "role": "positive", "checks": [name],
                "source_commit": self.head, "spec_commit": self.head, "spec_sha256": self.data["spec"]["sha256"],
                "argv": ["synthetic-fixture", name], "exit_code": 0, "expected_exit_code": 0,
                "log": name + "-log", "markers": ["SYNTHETIC FIXTURE PASS " + name],
            })
            self.data["checks"].append({"id": name, "status": "passed", "commands": [name]})
        self.derive_layers()

    def reseal(self):
        self.write_json(self.source / "intent/contract.json", self.spec)
        self.write_json(self.source / "intent/implementation-map.json", self.mapping)
        self.git("add", "-A")
        self.git("commit", "--allow-empty", "-qm", "Seal synthetic fixture")
        self.head = self.git("rev-parse", "HEAD")
        self.data.update(source_commit=self.head, spec_commit=self.head)
        self.inventory["source_commit"] = self.head
        self.sync_inventory()
        self.data["spec"]["sha256"] = sha(self.source / "intent/contract.json")
        self.data["sources"] = [{"path": path, "sha256": sha(self.source / path)}
                                for path in self.git("ls-files").splitlines()]
        for command in self.data["commands"]:
            command.update(source_commit=self.head, spec_commit=self.head, spec_sha256=self.data["spec"]["sha256"])

    def derive_layers(self):
        states = {c["id"]: c["status"] for c in self.data["checks"]}
        self.data["layers"] = []
        for layer in self.spec["layers"]:
            statuses = {states[d["id"]] for d in self.spec["checks"] if d["layer"] == layer}
            status = "failed" if "failed" in statuses else "blocked" if "blocked" in statuses else "passed"
            self.data["layers"].append({"id": layer, "status": status, "reason": "Synthetic fixture boundary"})

    def block(self, name):
        next(c for c in self.data["checks"] if c["id"] == name).update(
            status="blocked", commands=[], reason="Synthetic missing capability")
        self.data["commands"] = [c for c in self.data["commands"] if c["id"] != name]
        self.derive_layers()

    def test_complete_integrated_fixture_qualifies_both_gates(self):
        result = self.check(candidate=True, release=True)
        self.assertTrue(result["candidate_ready"])
        self.assertTrue(result["release_qualified"])
        self.assertEqual(result["requirements"], 8)

    def test_candidate_allows_explicit_final_only_blockers(self):
        self.block("backend-command-application")
        self.block("physical-device")
        self.block("install-update")
        self.assertTrue(self.check(candidate=True)["candidate_ready"])
        self.assertFalse(self.check()["release_qualified"])
        with self.assertRaisesRegex(ValueError, "release gate"):
            self.check(release=True)

    def test_candidate_rejects_missing_native_positive(self):
        self.block("native-thread-receipts")
        with self.assertRaisesRegex(ValueError, "candidate gate"):
            self.check(candidate=True)

    def test_candidate_rejects_missing_runtime_positive(self):
        self.block("app-runtime-smoke")
        with self.assertRaisesRegex(ValueError, "candidate gate"):
            self.check(candidate=True)

    def test_negative_only_candidate_gate_is_rejected(self):
        for command in self.data["commands"]:
            command.update(role="negative", exit_code=1, expected_exit_code=1)
        with self.assertRaisesRegex(ValueError, "positive"):
            self.check(candidate=True)

    def test_incomplete_requirement_registry_rejected(self):
        self.spec["requirements"].remove("EC-006")
        for definition in self.spec["checks"]:
            definition["requirements"] = [r for r in definition["requirements"] if r != "EC-006"] or ["EC-003"]
        self.reseal()
        self.rejected("integrated requirements")

    def test_stale_spec_commit_rejected(self):
        self.data["spec_commit"] = self.upstream
        self.rejected("spec commit")

    def test_stale_command_spec_commit_rejected(self):
        self.data["commands"][0]["spec_commit"] = self.upstream
        self.rejected("command.*spec")

    def test_missing_current_implementation_mapping_rejected(self):
        self.mapping["files"].pop()
        self.reseal()
        self.rejected("implementation map")

    def test_inventory_cannot_reassign_reviewed_mapping(self):
        self.inventory["files"][0].update(requirements=["EX-001"], checks=["reasoning-angle"])
        self.sync_inventory()
        self.rejected("implementation map")

    def test_upstream_cannot_move_to_shrink_diff(self):
        self.data["upstream_commit"] = self.head
        self.rejected("upstream")

    def test_candidate_gate_cannot_omit_runtime(self):
        self.spec["gates"]["candidate"].remove("app-runtime-smoke")
        self.reseal()
        self.rejected("candidate.*checks")

    def test_final_gate_cannot_omit_backend(self):
        self.spec["gates"]["release"].remove("backend-command-application")
        self.reseal()
        self.rejected("release.*checks")

    def test_missing_gate_definition_rejected(self):
        self.spec.pop("gates")
        self.reseal()
        self.rejected("gates")

    def refresh(self, *args):
        import subprocess
        import sys
        script = SCRIPT.with_name("refresh_inventory.py")
        self.assertTrue(script.is_file(), "inventory refresh implementation is missing")
        return subprocess.run([sys.executable, "-B", str(script), "--source-root", str(self.source), *args],
                              capture_output=True, text=True)

    def test_refresh_checks_exact_mapping(self):
        result = self.refresh("--check")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_refresh_rejects_unknown_new_path(self):
        (self.source / "unknown.kt").write_text("new source needs reviewed mapping")
        result = self.refresh("--refresh")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("unmapped", result.stdout)

    def test_seal_produces_only_blocked_template_and_real_hashes(self):
        result = self.refresh("--seal", str(self.evidence / "sealed"))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        sealed = json.loads((self.evidence / "sealed/manifest-template.json").read_text())
        self.assertEqual(sealed["source_commit"], self.head)
        self.assertEqual(sealed["spec_commit"], self.head)
        self.assertEqual(sealed["spec"]["sha256"], sha(self.source / "intent/contract.json"))
        self.assertEqual(sealed["commands"], [])
        self.assertEqual({c["status"] for c in sealed["checks"]}, {"blocked"})
        self.data, self.evidence = sealed, self.evidence / "sealed"
        self.assertFalse(self.check()["candidate_ready"])
        with self.assertRaisesRegex(ValueError, "candidate gate"):
            self.check(candidate=True)

    def test_seal_rejects_dirty_source(self):
        (self.source / "code.txt").write_text("dirty")
        result = self.refresh("--seal", str(self.evidence / "sealed"))
        self.assertEqual(result.returncode, 1)
        self.assertIn("dirty", result.stdout)

    def test_staged_dirty_source_rejected(self):
        (self.source / "code.txt").write_text("changed after freeze")
        self.git("add", "code.txt")
        self.rejected("dirty")


if __name__ == "__main__":
    unittest.main()
