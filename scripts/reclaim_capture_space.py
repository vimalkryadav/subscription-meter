#!/usr/bin/env python3
"""Return space freed by CaptureCompactor to disk, one finished request_details chunk at a time.

Clearing an inline body after a verified rebuild frees space inside a daily chunk, but the
chunk's files only shrink when rewritten. This rewrites (VACUUM FULL) older chunks whose
bodies are all converted. It never deletes rows: the row count is checked before and after,
and any difference stops the run. Default is a dry run; --apply rewrites.
"""
import argparse
import re
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import backup_database as db

CHUNK = re.compile(r'_timescaledb_internal\._hyper_\d+_\d+_chunk')
SPARE = 5 * 1024**3


def decide(rows, pending, size, free):
    """Why a chunk is skipped, or None when it may be rewritten."""
    if pending:
        return 'conversion not finished'
    if rows == 0:
        return 'empty'
    if free < size + SPARE:
        return 'not enough free space for a rewrite'
    return None


def chunks(container):
    out = db.sql(container, "SELECT chunk_schema||'.'||chunk_name FROM timescaledb_information.chunks "
                            "WHERE hypertable_name='request_details' AND range_end <= date_trunc('day', now()) "
                            "ORDER BY range_start;")
    names = [line for line in out.splitlines() if line]
    if any(not CHUNK.fullmatch(name) for name in names):
        raise RuntimeError('Unexpected chunk name')
    return names


def stats(container, chunk):
    line = db.sql(container, "SELECT count(*), count(*) FILTER (WHERE body_format=1 AND request_body IS NOT NULL "
                             "AND COALESCE(body_check,0) IN (0,1)), pg_total_relation_size('" + chunk + "') FROM " + chunk + ";")
    rows, pending, size = (int(v) for v in line.split('|'))
    return rows, pending, size


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Rewrite eligible chunks; default only reports')
    args = parser.parse_args()
    container, _ = db.source()
    total_before = total_after = 0
    for chunk in chunks(container):
        rows, pending, size = stats(container, chunk)
        reason = decide(rows, pending, size, db.docker_storage_free())
        if reason or not args.apply:
            print(f'{chunk}: {rows} rows, {size / 1024**2:.0f} MiB, {pending} awaiting conversion -> {reason or "eligible"}')
            continue
        db.sql(container, "SET lock_timeout='5s'; SET statement_timeout='30min'; VACUUM (FULL, ANALYZE) " + chunk + ";")
        after_rows, _, after_size = stats(container, chunk)
        if after_rows != rows:
            raise RuntimeError(f'Row count changed for {chunk}: {rows} -> {after_rows}; stopping')
        total_before += size
        total_after += after_size
        print(f'{chunk}: {rows} rows kept, {size / 1024**2:.0f} -> {after_size / 1024**2:.0f} MiB')
    if args.apply:
        print(f'Reclaimed {(total_before - total_after) / 1024**3:.2f} GiB; free on Docker filesystem: '
              f'{db.docker_storage_free() / 1024**3:.1f} GiB')


if __name__ == '__main__':
    main()
