#!/usr/bin/env python3
"""Private, bounded logical backups and isolated Timescale restore drills.

Never restores into the live database. Archive and inventory share one exported
read-only snapshot. No credentials, request contents, or raw DB errors are logged.
"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import select
import shutil
import subprocess
import time
import uuid
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
import backup_policy

ROOT = Path(__file__).resolve().parents[1]
STORE = Path(os.environ.get('CONTROL_USAGE_BACKUP_DIR', '/home/coder/control-usage-backups'))
REPORT = ROOT / 'deploy/operations/backup-status.json'
BUDGET = backup_policy.load()
MAX_ARCHIVE = BUDGET["max_archive_bytes"]
MAX_DATABASE = BUDGET["max_database_bytes"]
MAX_STORE = BUDGET["max_store_bytes"]
MIN_FREE = BUDGET["min_free_bytes"]
LEGACY_TABLES = ('members', 'portal_accounts', 'portal_sessions', 'portal_key_requests',
          'requests', 'request_details', 'request_events', 'gateway_access',
          'request_assessments', 'provider_quota_snapshots', 'subscription_invoices')
V2_TABLES = LEGACY_TABLES + ('audit_operations', 'audit_payload_parts', 'accounting_price_versions',
                         'admin_action_log', 'control_schema_history', 'gateway_instances',
                         'coordination_locks', 'gateway_admissions', 'auth_attempts')

TABLES = V2_TABLES + ('portal_refresh_families', 'portal_access_tokens', 'portal_refresh_tokens')

def inventory_sql(version=3):
    if version not in (1, 2, 3): raise ValueError('Unsupported inventory version')
    tables = {1: LEGACY_TABLES, 2: V2_TABLES, 3: TABLES}[version]
    query = 'SELECT json_build_object(' + ','.join(
        "'%s',(SELECT count(*) FROM %s)" % (name, name) for name in tables)
    query += ", 'adminCount',(SELECT count(*) FROM portal_accounts WHERE role='admin'), 'hypertables',(SELECT count(*) FROM timescaledb_information.hypertables), 'timescaleVersion',(SELECT extversion FROM pg_extension WHERE extname='timescaledb')"
    if version >= 2:
        query += ", 'schemaFingerprint',(SELECT md5(string_agg(name||':'||checksum,',' ORDER BY name)) FROM control_schema_history)"
        query += ", 'orphanPayloadParts',(SELECT count(*) FROM audit_payload_parts p WHERE NOT EXISTS(SELECT 1 FROM audit_operations o WHERE o.operation_id=p.operation_id))"
    return query + ')::text;'

INVENTORY_SQL = inventory_sql()


def now():
    return datetime.now(timezone.utc).isoformat()


def docker_command(args):
    prefix = ['sudo', '-n', 'docker'] if os.environ.get('CONTROL_USAGE_DOCKER_SUDO') == '1' else ['docker']
    return prefix + args


def docker(args, **kwargs):
    return subprocess.run(docker_command(args), check=True, timeout=kwargs.pop('timeout', 60),
                          stderr=subprocess.DEVNULL, **kwargs)


def bounded_output(command, target, limit, seconds):
    """Enforce bytes in the unprivileged writer; sudo may reset child rlimits."""
    proc = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
    deadline = time.monotonic() + seconds
    written = 0
    try:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('Archive output timed out')
            if not select.select([proc.stdout], [], [], min(remaining, 1))[0]:
                continue
            chunk = os.read(proc.stdout.fileno(), 65536)
            if not chunk:
                if proc.wait(timeout=max(.1, deadline - time.monotonic())) != 0:
                    raise RuntimeError('Archive process failed')
                return
            if written + len(chunk) > limit:
                raise RuntimeError('Archive size limit exceeded')
            target.write(chunk)
            written += len(chunk)
    finally:
        proc.stdout.close()
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()


def output(args, **kwargs):
    return docker(args, stdout=subprocess.PIPE, text=True, **kwargs).stdout.strip()


def write_json(path, data, mode=0o600):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + '.tmp')
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, 'w') as handle:
        os.fchmod(handle.fileno(), mode)
        json.dump(data, handle)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temp, path)


def update_status(**changes):
    try:
        data = json.loads(REPORT.read_text())
    except (OSError, ValueError):
        data = {}
    data.update(changes)
    # This separate report deliberately contains no archive paths or DB data.
    write_json(REPORT, data, 0o644)


def source():
    ids = output(['ps', '--filter', 'label=com.docker.compose.project=subscription-meter',
                  '--filter', 'label=com.docker.compose.service=timescaledb', '--format', '{{.ID}}']).splitlines()
    if len(ids) != 1 or not re.fullmatch('[0-9a-f]{12,64}', ids[0]):
        raise RuntimeError('Exactly one project database must be running')
    image = output(['inspect', '--format', '{{.Image}}', ids[0]])
    if not re.fullmatch('sha256:[0-9a-f]{64}', image):
        raise RuntimeError('Database image is not immutable')
    return ids[0], image


def sql(container, query, database='meter', tcp=False):
    return output(['exec', '-i', container, 'psql', '-XAtq', '-v', 'ON_ERROR_STOP=1',
                   '-U', 'meter', '-d', database] + (['-h', '127.0.0.1'] if tcp else []), input=query)


def inventory(container, version=3):
    return json.loads(sql(container, inventory_sql(version), tcp=True))


def read_line(process):
    if not select.select([process.stdout], [], [], 70)[0]:
        raise TimeoutError('Snapshot query timed out')
    line = process.stdout.readline().strip()
    if not line:
        raise RuntimeError('Snapshot query failed')
    return line


@contextmanager
def snapshot(container):
    prefix = ['sudo', '-n', 'docker'] if os.environ.get('CONTROL_USAGE_DOCKER_SUDO') == '1' else ['docker']
    proc = subprocess.Popen(prefix + ['exec', '-i', container, 'psql', '-XAtq', '-v', 'ON_ERROR_STOP=1',
                                     '-U', 'meter', '-d', 'meter'], stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
    try:
        proc.stdin.write("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET statement_timeout='60s'; SET idle_in_transaction_session_timeout='1000s'; SELECT pg_export_snapshot();\n")
        proc.stdin.flush()
        identifier = read_line(proc)
        if not re.fullmatch('[0-9A-Fa-f-]+', identifier):
            raise RuntimeError('Invalid snapshot identifier')
        # The release checkout can advance before its additive migration is applied.
        # Choose the inventory from the same exported snapshot, preserving pre-upgrade backups.
        proc.stdin.write("SELECT CASE WHEN to_regclass('portal_refresh_families') IS NULL THEN 2 ELSE 3 END;\n")
        proc.stdin.flush()
        version = int(read_line(proc))
        proc.stdin.write(inventory_sql(version) + '\n')
        proc.stdin.flush()
        expected = json.loads(read_line(proc))
        yield identifier, expected, version
    finally:
        try:
            proc.stdin.close()
            proc.wait(timeout=10)
        except (OSError, subprocess.TimeoutExpired):
            proc.kill()
            proc.wait()
        proc.stdout.close()


def digest(path):
    hashed = hashlib.sha256()
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b''):
            hashed.update(chunk)
    return hashed.hexdigest()


def completed_archives():
    result = []
    for path in sorted(STORE.glob('backup-*.json')):
        if re.fullmatch(r'backup-\d{8}T\d{6}Z-[0-9a-f]{8}\.json', path.name) and not path.is_symlink():
            data = json.loads(path.read_text())
            archive = path.with_suffix('.dump')
            if archive.is_file() and not archive.is_symlink():
                result.append((path, archive, data))
    return result


def docker_storage_free():
    path = output(['info', '--format', '{{.DockerRootDir}}'])
    if not path.startswith('/') or '\n' in path:
        raise RuntimeError('Docker storage location is unavailable')
    command = ['df', '-Pk', path]
    if os.environ.get('CONTROL_USAGE_DOCKER_SUDO') == '1':
        command = ['sudo', '-n'] + command
    result = subprocess.run(command, check=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=10)
    return int(result.stdout.strip().splitlines()[-1].split()[3]) * 1024


def capacity(database_bytes):
    used = sum(p.stat().st_size for p in STORE.iterdir() if p.is_file())
    if database_bytes > MAX_DATABASE or used + MAX_ARCHIVE > MAX_STORE:
        raise RuntimeError('Backup storage budget requires review')
    if shutil.disk_usage(STORE).free < max(MIN_FREE, database_bytes * 2 + MAX_ARCHIVE):
        raise RuntimeError('Insufficient free space for safe backup and restore')
    if docker_storage_free() < max(MIN_FREE, database_bytes * 2 + MAX_ARCHIVE):
        raise RuntimeError('Insufficient Docker storage for safe restore')


def capacity_report():
    """Fresh size-only observations; never runs a dump, restore or retention cleanup."""
    result = {'schemaVersion': 1, 'generatedAt': now(), 'status': 'unknown', 'archiveObservedAt': None, 'rows': []}
    try:
        container, _ = source()
        size = int(sql(container, "BEGIN READ ONLY; SET LOCAL statement_timeout='5s'; SELECT pg_database_size(current_database()); COMMIT;"))
        archives = completed_archives()
        if not archives: raise ValueError('No completed archive')
        _, archive, meta = archives[-1]
        result['archiveObservedAt'] = meta['createdAt']
        used = sum(p.stat().st_size for p in STORE.iterdir() if p.is_file())
        required = max(MIN_FREE, size * 2 + MAX_ARCHIVE)
        observations = [('archive', archive.stat().st_size, MAX_ARCHIVE, 0),
                        ('database', size, MAX_DATABASE, 0),
                        ('store', used, MAX_STORE, MAX_ARCHIVE),
                        ('backup_free', required, shutil.disk_usage(STORE).free, 0),
                        ('docker_free', required, docker_storage_free(), 0)]
        for name, value, limit, reserve in observations:
            state = 'failed' if value + reserve > limit else 'warning' if value * 100 >= limit * BUDGET['warning_percent'] else 'ok'
            result['rows'].append({'id': name, 'usedBytes': value, 'limitBytes': limit,
                                   'reserveBytes': reserve, 'remainingBytes': limit-value-reserve, 'state': state})
        states = {row['state'] for row in result['rows']}
        result['status'] = 'failed' if 'failed' in states else 'warning' if 'warning' in states else 'ok'
    except Exception:
        result['status'] = 'unknown'
        result['rows'] = []
    return result


def write_capacity_report():
    write_json(REPORT.with_name('backup-capacity.json'), capacity_report(), 0o644)


def prune():
    # Only this program's completed backup pairs; never delete arbitrary files.
    archives = completed_archives()
    verified = [archive for _, archive, meta in archives if meta.get('verifiedAt')]
    protected = verified[-1] if verified else None
    for manifest, archive, _ in archives[:-BUDGET['retain_completed']]:
        if archive == protected:
            continue
        archive.unlink()
        manifest.unlink()


def backup():
    container, image = source()
    prune()
    database_bytes = int(sql(container, 'SELECT pg_database_size(current_database());'))
    capacity(database_bytes)
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    archive = STORE / ('backup-' + stamp + '-' + uuid.uuid4().hex[:8] + '.dump')
    partial = archive.with_suffix('.partial')
    try:
        with snapshot(container) as (identifier, expected, version):
            with partial.open('xb') as handle:
                os.chmod(partial, 0o600)
                # Bound both output here and the DB process inside the source container.
                command = docker_command(['exec', container, 'timeout', '900', 'pg_dump', '-U', 'meter', '-d', 'meter', '-Fc',
                                          '--no-owner', '--no-acl', '--lock-wait-timeout=10s', '--snapshot=' + identifier])
                bounded_output(command, handle, MAX_ARCHIVE, 920)
                handle.flush()
                os.fsync(handle.fileno())
        with partial.open('rb') as handle:
            if handle.read(5) != b'PGDMP':
                raise RuntimeError('Invalid archive header')
        os.replace(partial, archive)
        meta = {'createdAt': now(), 'image': image, 'sha256': digest(archive), 'databaseBytes': database_bytes,
                'bytes': archive.stat().st_size, 'inventoryVersion': version, 'inventory': expected}
        write_json(archive.with_suffix('.json'), meta)
        update_status(lastSuccessAt=meta['createdAt'], lastAttemptState='ok', backupBytes=meta['bytes'])
        prune()
        return archive, meta
    finally:
        partial.unlink(missing_ok=True)


def restore(archive, meta):
    if archive.is_symlink() or not re.fullmatch('sha256:[0-9a-f]{64}', meta.get('image', '')):
        raise RuntimeError('Unsafe restore source')
    if digest(archive) != meta['sha256']:
        raise RuntimeError('Archive checksum mismatch')
    if shutil.disk_usage(STORE).free < MIN_FREE:
        raise RuntimeError('Insufficient restore space')
    database_bytes = int(meta.get('databaseBytes', MAX_DATABASE))
    if not 0 < database_bytes <= MAX_DATABASE or docker_storage_free() < max(MIN_FREE, database_bytes * 2 + MAX_ARCHIVE):
        raise RuntimeError('Insufficient Docker restore space or invalid source size')
    name = 'control-usage-restore-' + uuid.uuid4().hex
    container = None
    try:
        container = output(['run', '-d', '--rm', '--name', name, '--network', 'none',
                            '--label', 'dev.control-usage.restore-drill=true', '--memory', str(BUDGET['restore_memory_mib'])+'m', '--cpus', str(BUDGET['restore_cpus']),
                            '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', '-e', 'POSTGRES_USER=meter', '-e', 'POSTGRES_DB=meter',
                            '-e', 'TIMESCALEDB_TELEMETRY=off', '-e', 'NO_TS_TUNE=true',
                            '--mount', 'type=bind,src=' + str(archive.resolve()) + ',dst=/backup.dump,readonly',
                            meta['image'], '-c', 'timescaledb.max_background_workers=0',
                            '-c', 'shared_buffers=64MB', '-c', 'max_connections=10'], timeout=90)
        deadline = time.monotonic() + 90
        while True:
            try:
                if sql(container, 'SELECT 1;', tcp=True) == '1':
                    break
            except subprocess.CalledProcessError:
                if time.monotonic() >= deadline:
                    raise RuntimeError('Isolated restore database did not become ready') from None
            time.sleep(1)
        sql(container, 'CREATE EXTENSION IF NOT EXISTS timescaledb; SELECT timescaledb_pre_restore();', tcp=True)
        docker(['exec', container, 'pg_restore', '-U', 'meter', '-d', 'meter', '--no-owner', '--no-acl',
                '--exit-on-error', '/backup.dump'], stdout=subprocess.DEVNULL, timeout=900)
        sql(container, 'SELECT timescaledb_post_restore();', tcp=True)
        if inventory(container, meta.get('inventoryVersion', 1)) != meta['inventory']:
            raise RuntimeError('Restored inventory differs from the backup snapshot')
    finally:
        if container:
            # Exact container created above, with its anonymous volumes. No live volume is attached.
            docker(['rm', '-f', '-v', container], stdout=subprocess.DEVNULL)
        else:
            # A timed-out docker run can still have created its uniquely named container.
            try:
                owned = output(['inspect', '--format', '{{index .Config.Labels "dev.control-usage.restore-drill"}}', name])
                if owned == 'true':
                    docker(['rm', '-f', '-v', name], stdout=subprocess.DEVNULL)
            except subprocess.CalledProcessError:
                pass
    meta['verifiedAt'] = now()
    write_json(archive.with_suffix('.json'), meta)
    update_status(lastRestoreAt=now(), lastRestoreState='ok')
    prune()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-latest', action='store_true', help='Restore-test the newest completed backup only')
    parser.add_argument('--verify', action='store_true', help='Create a backup and immediately restore-test it')
    parser.add_argument('--capacity', action='store_true', help='Collect sanitized size-only capacity observations')
    args = parser.parse_args()
    if args.capacity:
        write_capacity_report()
        print('Backup capacity observations collected.')
        return
    os.umask(0o077)
    STORE.mkdir(parents=True, exist_ok=True, mode=0o700)
    if STORE.is_symlink() or STORE.stat().st_uid != os.getuid():
        raise RuntimeError('Backup directory must be owned by the operator and not a symlink')
    os.chmod(STORE, 0o700)
    with (STORE / '.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print('Backup maintenance is already running.')
            return
        try:
            if args.verify_latest:
                candidates = completed_archives()
                if not candidates:
                    raise RuntimeError('No completed backup is available')
                _, archive, meta = candidates[-1]
            else:
                update_status(lastAttemptAt=now(), lastAttemptState='running')
                archive, meta = backup()
            if args.verify or args.verify_latest:
                update_status(lastRestoreAttemptAt=now(), lastRestoreState='running')
                restore(archive, meta)
            print(json.dumps({'backup': 'ok', 'restore': 'ok' if args.verify or args.verify_latest else 'not_run'}))
        except Exception as failure:
            if args.verify_latest or (args.verify and 'archive' in locals()):
                update_status(lastRestoreState='failed')
            else:
                update_status(lastAttemptState='failed')
            # subprocess errors can contain sensitive query data; emit only the exception class.
            print('Backup maintenance failed (' + type(failure).__name__ + '). Review private state and capacity.')
            raise SystemExit(1) from None


if __name__ == '__main__':
    main()
