"""Shared, validated backup/restore budgets. Values are operator configuration, never secrets."""
import json
from pathlib import Path


def load(path=None):
    path = path or Path(__file__).resolve().parents[1] / 'deploy/backup-budget.json'
    values = json.loads(path.read_text())
    integers = ('max_archive_bytes', 'max_database_bytes', 'max_store_bytes', 'min_free_bytes',
                'retain_completed', 'warning_percent', 'restore_memory_mib')
    if set(values) != set(integers) | {'restore_cpus'}:
        raise ValueError('Unexpected backup policy fields')
    if any(type(values[key]) is not int or values[key] <= 0 for key in integers):
        raise ValueError('Backup policy requires positive integers')
    if not 1 <= values['retain_completed'] <= 30 or not 50 <= values['warning_percent'] <= 95:
        raise ValueError('Invalid retention or warning threshold')
    # Retained archives + an older protected verified copy + one pending archive,
    # plus manifests/report overhead. Every full-sized archive must fit.
    if values['max_store_bytes'] < (values['retain_completed'] + 2) * values['max_archive_bytes'] + 64 * 1024**2:
        raise ValueError('Backup store cannot hold retention, protected and pending archives')
    if values['max_database_bytes'] < values['max_archive_bytes'] or values['min_free_bytes'] < values['max_archive_bytes']:
        raise ValueError('Backup source/reserve budget is inconsistent')
    if not 256 <= values['restore_memory_mib'] <= 2048 or type(values['restore_cpus']) not in (int, float) or not .1 <= values['restore_cpus'] <= 2:
        raise ValueError('Restore resource bounds are invalid')
    return values
