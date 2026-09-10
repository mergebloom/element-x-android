import json
from pathlib import Path
import tempfile
import unittest

from evidence import CLASS, EXPECTED, elf_identity, parse_instrumentation, write_results
from fixture_server import configuration, is_receipt_write


def runner_output():
    lines = []
    for case in sorted(EXPECTED):
        for code in (1, 0):
            lines += [f"INSTRUMENTATION_STATUS: class={CLASS}", f"INSTRUMENTATION_STATUS: test={case}",
                      f"INSTRUMENTATION_STATUS: numtests={len(EXPECTED)}", f"INSTRUMENTATION_STATUS_CODE: {code}"]
    return "\n".join(lines + ["INSTRUMENTATION_CODE: -1"])


class EvidenceTests(unittest.TestCase):
    def test_accepts_exact_nonzero_plan(self):
        result = parse_instrumentation(runner_output())
        self.assertTrue(result["passed"])
        self.assertEqual(len(EXPECTED), result["observed"])

    def test_rejects_zero_tests_even_with_success_exit(self):
        self.assertFalse(parse_instrumentation("INSTRUMENTATION_CODE: -1")["passed"])

    def test_rejects_wrong_test_count(self):
        self.assertFalse(parse_instrumentation(runner_output().replace(f"numtests={len(EXPECTED)}", "numtests=0"))["passed"])

    def test_rejects_skip(self):
        self.assertFalse(parse_instrumentation(runner_output().replace("STATUS_CODE: 0", "STATUS_CODE: -3", 1))["passed"])

    def test_rejects_failure(self):
        self.assertFalse(parse_instrumentation(runner_output().replace("STATUS_CODE: 0", "STATUS_CODE: -2", 1))["passed"])

    def test_rejects_crash(self):
        self.assertFalse(parse_instrumentation(runner_output().replace("INSTRUMENTATION_CODE: -1", ""))["passed"])

    def test_rejects_duplicate(self):
        self.assertFalse(parse_instrumentation(runner_output() + "\n" + runner_output())["passed"])

    def test_never_exports_raw_stream_or_stack(self):
        result = parse_instrumentation("INSTRUMENTATION_STATUS: stream=PRIVATE_BODY\nINSTRUMENTATION_STATUS: stack=PRIVATE_TOKEN\n" + runner_output())
        with tempfile.TemporaryDirectory() as directory:
            write_results(result, Path(directory))
            for file in Path(directory).iterdir():
                self.assertNotIn("PRIVATE_", file.read_text())
            self.assertEqual(set(EXPECTED), set(json.loads((Path(directory) / "results.json").read_text())["cases"]))

    def test_failure_diagnostics_keep_only_exception_class_and_owned_source_lines(self):
        stack = (
            "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: PRIVATE_BODY token=PRIVATE_TOKEN\n"
            "\tat io.element.android.libraries.matrix.impl.threads.NativeThreadReceiptTest.check(NativeThreadReceiptTest.kt:42)\n"
            "Caused by: org.matrix.rustcomponents.sdk.ClientException$Generic: PRIVATE_TOKEN\n"
            "\tat java.lang.reflect.Method.invoke(Native Method)\n"
            "INSTRUMENTATION_STATUS_CODE: -2"
        )
        result = parse_instrumentation(runner_output().replace("INSTRUMENTATION_STATUS_CODE: 0", stack, 1))
        diagnostic = result["diagnostics"][sorted(EXPECTED)[0]]
        self.assertEqual(["java.lang.AssertionError", "org.matrix.rustcomponents.sdk.ClientException$Generic"], diagnostic["exception_classes"])
        self.assertEqual(42, diagnostic["frames"][0]["line"])
        self.assertEqual("NativeThreadReceiptTest.kt", diagnostic["frames"][0]["file"])
        self.assertNotIn("PRIVATE_", json.dumps(result))
        self.assertFalse(result["passed"])
        with tempfile.TemporaryDirectory() as directory:
            write_results(result, Path(directory))
            self.assertNotIn("PRIVATE_", (Path(directory) / "junit.xml").read_text())
            self.assertIn("NativeThreadReceiptTest.kt", (Path(directory) / "junit.xml").read_text())

    def test_rejects_non_elf_native_identity(self):
        with self.assertRaises(ValueError):
            elf_identity(b"not an ELF binary")

    def test_audit_includes_redundant_and_private_writes(self):
        self.assertTrue(is_receipt_write("POST", "/_matrix/client/v3/rooms/room/receipt/m.read/event"))
        self.assertTrue(is_receipt_write("POST", "/_matrix/client/v3/rooms/room/receipt/m.read.private/event"))
        self.assertTrue(is_receipt_write("POST", "/_matrix/client/v3/rooms/room/read_markers"))
        self.assertFalse(is_receipt_write("GET", "/_matrix/client/v1/rooms/room/threads"))
        self.assertFalse(is_receipt_write("POST", "/_matrix/client/unstable/org.matrix.simplified_msc3575/sync"))

    def test_server_is_loopback_only_no_federation_and_sss_explicit(self):
        config = configuration(Path("/tmp/synthetic-only"))
        self.assertEqual(["127.0.0.1"], config["listeners"][0]["bind_addresses"])
        self.assertEqual([], config["federation_domain_whitelist"])
        self.assertFalse(config["send_federation"])
        self.assertTrue(config["experimental_features"]["msc3575_enabled"])
        self.assertFalse(config["push"]["enabled"])


if __name__ == "__main__":
    unittest.main()
