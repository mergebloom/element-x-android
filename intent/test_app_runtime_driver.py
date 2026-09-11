"""Host-only fixture-driver regressions; these do not claim Android execution."""
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("app_runtime_driver", Path(__file__).resolve().parents[1] / "tools/app-runtime-smoke.py")
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)
UI = b'<hierarchy><node text="Chats"/></hierarchy>'
DUMPED = b'UI hierchary dumped to: /sdcard/candidate-ui.xml\n'


class UiDumpTest(unittest.TestCase):
    def setUp(self):
        driver.RESULT.pop('ui_dump_failures', None)

    def test_valid_dump_requires_fresh_file_and_hierarchy(self):
        with patch.object(driver, 'adb', side_effect=[b'', DUMPED, UI]) as adb:
            self.assertEqual({'Chats', ''}, driver.labels(driver.nodes()))
        self.assertEqual(('shell', 'rm', '-f', '/sdcard/candidate-ui.xml'), adb.call_args_list[0].args)

    def test_transient_malformed_dump_retries_without_recording_content(self):
        secret_blob = b'cat: unavailable PRIVATE_FIXTURE_PASSWORD'
        with patch.object(driver, 'adb', side_effect=[b'', DUMPED, secret_blob, b'', DUMPED, UI]), patch.object(driver.time, 'sleep'):
            self.assertIn('Chats', driver.labels(driver.nodes()))
        self.assertNotIn('PRIVATE_FIXTURE_PASSWORD', json.dumps(driver.RESULT))
        self.assertEqual('malformed_xml', driver.RESULT['ui_dump_failures'][-1]['reason'])

    def test_failed_dump_never_reads_an_old_file(self):
        with patch.object(driver, 'adb', side_effect=[b'', b'ERROR: null root node', b'', DUMPED, UI]) as adb, patch.object(driver.time, 'sleep'):
            self.assertIn('Chats', driver.labels(driver.nodes()))
        self.assertEqual(1, sum(call.args[0] == 'exec-out' for call in adb.call_args_list))

    def test_persistent_invalid_document_fails_closed_with_bounded_attempts(self):
        with patch.object(driver, 'adb', side_effect=[b'', DUMPED, b'<not-a-hierarchy/>'] * 6) as adb, patch.object(driver.time, 'sleep'):
            with self.assertRaisesRegex(RuntimeError, 'fresh Android UI hierarchy'):
                driver.nodes()
        self.assertEqual(18, adb.call_count)
        self.assertEqual(6, len(driver.RESULT['ui_dump_failures']))

    def test_empty_hierarchy_is_not_login_success(self):
        with patch.object(driver, 'adb', side_effect=[b'', DUMPED, b'<hierarchy/>'] * 6), patch.object(driver.time, 'sleep'):
            with self.assertRaises(RuntimeError):
                driver.nodes()


if __name__ == '__main__':
    unittest.main()
