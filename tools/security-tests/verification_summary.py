"""Emit test totals and changed-file metadata, never test logs or secret values."""
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
totals = dict(tests=0, failures=0, errors=0, skipped=0)
suites = []
for path in sorted((root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
    suite = ET.parse(path).getroot()
    counts = {key: int(suite.get(key, '0')) for key in totals}
    for key in totals:
        totals[key] += counts[key]
    suites.append({'suite': suite.get('name'), **counts})
print(json.dumps({'android_latest_results': totals, 'failed_or_security_suites': [
    suite for suite in suites if suite['failures'] or suite['errors'] or '.security.' in suite['suite']]}))

own = ['.gitignore', 'firestore.rules', 'windows-agent/frontend/package-lock.json',
       'windows-agent/server/api_router.py', 'windows-agent/server/http_server.py',
       'windows-agent/server/ui_bridge.py', 'windows-agent/dashboard/assets/js/views/activity.js']
result = subprocess.run(['git', 'diff', '--check', '--', *own], cwd=root, capture_output=True)
print(json.dumps({'own_tracked_diff_whitespace_check_exit': result.returncode}))

for name in ['windows-agent/frontend', 'tools/security-tests', 'tools/license-generator']:
    lock = json.loads((root / name / 'package-lock.json').read_text(encoding='utf-8'))
    versions = {key: value.get('version') for key, value in lock.get('packages', {}).items()
                if key in ['node_modules/next', 'node_modules/firebase-admin',
                           'node_modules/firebase-tools', 'node_modules/@firebase/rules-unit-testing',
                           'node_modules/firebase']}
    print(json.dumps({'lockfile': name, 'versions': versions}))
