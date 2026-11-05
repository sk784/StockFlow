#!/usr/bin/env python3
"""Test verification orchestration with fake Java, Gradle and adb; no Android build is performed."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).with_name('verify_android.sh')


class VerifyScriptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='stockflow-verify-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'scripts').mkdir()
        shutil.copy2(SOURCE, self.root / 'scripts/verify_android.sh')
        (self.root / 'backend').mkdir()
        (self.root / 'backend/test_backend.py').write_text('''
import os, unittest
class BackendTest(unittest.TestCase):
    def test_example(self):
        self.assertFalse(os.environ.get('MOCK_BACKEND_FAIL'))
''')
        (self.root / 'scripts/check_migrations.py').write_text("print('Mock migration check')\n")
        (self.root / 'fake-jdk/bin').mkdir(parents=True)
        self.executable('fake-jdk/bin/java', '#!/bin/sh\necho \'openjdk version "21.0.1"\' >&2\n')
        (self.root / 'fake-sdk/platforms/android-36').mkdir(parents=True)
        (self.root / 'fake-sdk/platforms/android-36/android.jar').touch()
        (self.root / 'fake-sdk/platform-tools').mkdir()
        self.executable('fake-sdk/platform-tools/adb', '''#!/bin/sh
printf 'List of devices attached\n%s\n' "${MOCK_DEVICES:-emulator-5554 device}"
''')
        self.executable('gradlew', '''#!/usr/bin/env bash
set -e
printf '%s\\0' "$@" >> gradle-arguments.bin
case "$*" in
    *:app:clean*)
        if [[ -n "${MOCK_CLEAN_FAIL:-}" ]]; then exit 42; fi
        rm -rf app/build
        ;;
    *:app:assembleDebug*)
        mkdir -p app/build/outputs/apk/debug app/build/reports/tests
        printf 'new fake APK' > app/build/outputs/apk/debug/app-debug.apk
        printf '<html>fake report</html>' > app/build/reports/tests/index.html
        exit "${MOCK_BUILD_RESULT:-0}"
        ;;
    *:app:connectedDebugAndroidTest*)
        exit "${MOCK_TEST_RESULT:-0}"
        ;;
esac
''')
        self.env = dict(os.environ)
        for key in ('ANDROID_SERIAL', 'STOCKFLOW_VERIFY_OUTPUT', 'MOCK_BACKEND_FAIL', 'MOCK_CLEAN_FAIL',
                    'MOCK_BUILD_RESULT', 'MOCK_TEST_RESULT', 'MOCK_DEVICES'):
            self.env.pop(key, None)
        self.env.update(JAVA_HOME=str(self.root / 'fake-jdk'), ANDROID_HOME=str(self.root / 'fake-sdk'))

    def executable(self, relative, text):
        path = self.root / relative
        path.write_text(text)
        path.chmod(0o755)

    def run_script(self, *arguments, **environment):
        result = subprocess.run(['bash', 'scripts/verify_android.sh', *arguments], cwd=self.root,
                                env=dict(self.env, **environment), text=True, capture_output=True, timeout=30)
        run = next((self.root / 'build/verification').glob('run-*'))
        rows = (run / 'results.tsv').read_text().splitlines()[1:]
        statuses = dict(line.split('\t') for line in rows)
        return result, run, statuses

    def test_success_collects_new_apk_reports_and_preserves_gradle_argument(self):
        result, run, statuses = self.run_script('--', '-Pexample=value with spaces')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual('0', statuses['android-build'])
        self.assertEqual('new fake APK', (run / 'artifacts/apk/debug/app-debug.apk').read_text())
        arguments = (self.root / 'gradle-arguments.bin').read_bytes().split(b'\0')
        self.assertIn(b'-Pexample=value with spaces', arguments)
        self.assertNotIn('instrumentation', statuses)

    def test_clean_failure_never_exports_stale_apk(self):
        previous = self.root / 'app/build/outputs/apk/debug/app-debug.apk'
        previous.parent.mkdir(parents=True)
        previous.write_text('stale APK')
        result, run, statuses = self.run_script(MOCK_CLEAN_FAIL='1')
        self.assertEqual(1, result.returncode)
        self.assertEqual('42', statuses['android-clean'])
        self.assertNotIn('android-build', statuses)
        self.assertFalse(list((run / 'artifacts').rglob('*.apk')))
        self.assertEqual('stale APK', previous.read_text())

    def test_build_failure_is_not_hidden_by_tee_or_connected_mode(self):
        result, run, statuses = self.run_script('--connected', MOCK_BUILD_RESULT='7')
        self.assertEqual(1, result.returncode)
        self.assertEqual('7', statuses['android-build'])
        self.assertNotIn('devices', statuses)
        self.assertIn('instrumentation-skipped-after-build-failure', statuses)
        self.assertIn('exit_code=1', (run / 'status.txt').read_text())

    def test_one_authorized_device_runs_instrumentation(self):
        result, _, statuses = self.run_script('--connected')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual('0', statuses['instrumentation'])

    def test_multiple_devices_require_explicit_selection(self):
        result, _, statuses = self.run_script('--connected', MOCK_DEVICES='first device\nsecond device')
        self.assertEqual(1, result.returncode)
        self.assertEqual('2', statuses['device-selection'])
        self.assertNotIn('instrumentation', statuses)

    def test_explicit_device_selection_is_supported(self):
        result, _, statuses = self.run_script('--connected', MOCK_DEVICES='first device\nsecond device', ANDROID_SERIAL='second')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual('0', statuses['instrumentation'])

    def test_instrumentation_failure_fails_verification(self):
        result, _, statuses = self.run_script('--connected', MOCK_TEST_RESULT='9')
        self.assertEqual(1, result.returncode)
        self.assertEqual('9', statuses['instrumentation'])

    def test_backend_failure_is_not_overwritten_by_successful_build(self):
        result, _, statuses = self.run_script(MOCK_BACKEND_FAIL='1')
        self.assertEqual(1, result.returncode)
        self.assertEqual('1', statuses['backend'])
        self.assertEqual('0', statuses['android-build'])

    def test_missing_sdk_stops_before_gradle_without_exporting_old_artifacts(self):
        result, run, statuses = self.run_script(ANDROID_HOME=str(self.root / 'missing-sdk'))
        self.assertEqual(2, result.returncode)
        self.assertEqual({}, statuses)
        self.assertFalse((self.root / 'gradle-arguments.bin').exists())
        self.assertFalse(list((run / 'artifacts').rglob('*.apk')))


if __name__ == '__main__':
    unittest.main(verbosity=2)
