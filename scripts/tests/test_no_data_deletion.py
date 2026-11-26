"""Captured data is never deleted. Fails when code reintroduces a deletion path.

Allowed exceptions are listed explicitly; adding one needs the owner's approval.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
CAPTURED = r'(requests|request_details|request_events|gateway_access|audit_operations|capture_blobs)'
PATTERNS = [
    re.compile(r'add_retention_policy', re.I),
    re.compile(r'drop_chunks', re.I),
    re.compile(r'\bDELETE\s+FROM\s+' + CAPTURED + r'\b', re.I),
    re.compile(r'\bTRUNCATE\b[^;]*\b' + CAPTURED + r'\b', re.I),
    re.compile(r'\bDROP\s+TABLE\b[^;]*\b' + CAPTURED + r'\b', re.I),
    re.compile(r'request_body\s*=\s*NULL', re.I),
]
# Historical migrations stay byte-identical (checksummed); the mock-data script
# removes only fixture rows that never were member traffic.
ALLOWED = {
    'deploy/timescale-migrations/V001__hypertables.sql',
    'deploy/timescale-migrations/V002__request_details_seven_days.sql',
    'deploy/remove-mock-data.sql',
    # Clears an inline body only after an exact rebuild from stored pieces is verified.
    'src/main/java/dev/subscriptionmeter/audit/CaptureCompactor.java',
}
SCANNED = ('deploy', 'scripts', 'src/main')


class NoDataDeletionTest(unittest.TestCase):
    def test_no_code_deletes_captured_data(self):
        offenders = []
        for folder in SCANNED:
            for path in (ROOT / folder).rglob('*'):
                rel = path.relative_to(ROOT).as_posix()
                if not path.is_file() or path.suffix not in ('.sql', '.py', '.java', '.sh') or rel in ALLOWED:
                    continue
                if rel.startswith('scripts/tests/'):
                    continue
                text = path.read_text(errors='ignore')
                for pattern in PATTERNS:
                    for match in pattern.finditer(text):
                        offenders.append(rel + ': ' + match.group(0))
        self.assertEqual([], offenders, 'Captured data must never be deleted')

    def test_default_retention_keeps_everything(self):
        properties = (ROOT / 'src/main/resources/application.properties').read_text()
        self.assertIn('meter.audit.retention-days=${AUDIT_RETENTION_DAYS:0}', properties)
        self.assertIn('meter.audit.metadata-retention-days=${AUDIT_METADATA_RETENTION_DAYS:0}', properties)
        compose = (ROOT / 'compose.yaml').read_text()
        self.assertIn('AUDIT_RETENTION_DAYS: ${AUDIT_RETENTION_DAYS:-0}', compose)


if __name__ == '__main__':
    unittest.main()
