#!/usr/bin/env python3
"""Validate a sanitized browser cutover receipt; never accesses services or credentials."""
import argparse
import json
import math
from pathlib import Path
import re


def require(condition, message):
    if not condition:
        raise ValueError(message)


def number(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def verify(report):
    require(isinstance(report, dict) and report.get('schemaVersion') == 1, 'Invalid receipt schema')
    before, after = report.get('before'), report.get('after')
    require(all(isinstance(v, str) and re.fullmatch('[a-f0-9]{40}', v) for v in (before, after)) and before != after, 'Distinct releases required')
    require(report.get('state') == 'complete' and not report.get('error'), 'Probe did not complete successfully')
    cutover = report.get('cutoverMs')
    require(number(cutover), 'Cutover observation missing')
    rows = report.get('rows')
    require(isinstance(rows, list) and len(rows) == 20 and all(isinstance(r, dict) for r in rows), 'Exactly twenty streams required')
    cohorts = {}
    for cohort, release in (('before', before), ('after', after)):
        batch = [r for r in rows if r.get('cohort') == cohort]
        require(len(batch) == 10 and all(type(r.get('index')) is int for r in batch) and {r['index'] for r in batch} == set(range(10)), 'Ten distinct streams per cohort required')
        for row in batch:
            require(row.get('status') == 200 and row.get('complete') is True and not row.get('error') and row.get('events') == 71, 'Stream failed or lost events')
            require(row.get('release') == release, 'Wrong serving release')
            fields = ('startedMs', 'firstMs', 'lastMs', 'finishedMs', 'serverSpanMs', 'deliverySpanMs')
            require(all(number(row.get(k)) for k in fields), 'Invalid stream timing')
            require(row['startedMs'] <= row['firstMs'] < row['lastMs'] <= row['finishedMs'], 'Invalid stream ordering')
            require(34000 <= row['serverSpanMs'] <= 45000 and row['finishedMs'] - row['startedMs'] <= 45000, 'Stream outside duration bounds')
            require(abs(row['deliverySpanMs'] - (row['lastMs'] - row['firstMs'])) <= 1 and row['deliverySpanMs'] >= row['serverSpanMs'] * .8, 'Stream buffered instead of incremental')
            if cohort == 'before':
                require(row['firstMs'] < cutover < row['lastMs'], 'Old stream did not span observed cutover')
            else:
                require(row['startedMs'] >= cutover, 'New stream started before observed cutover')
        cohorts[cohort] = batch
    require(max(r['firstMs'] for r in cohorts['after']) < min(r['lastMs'] for r in cohorts['before']), 'Twenty streams did not overlap across releases')
    return {'verified': True, 'streams': 20, 'events': 1420, 'before': before, 'after': after,
            'oldStreamsSpannedCutover': 10, 'newStreamsAfterCutover': 10,
            'protocols': sorted({r.get('protocol') or 'unavailable' for r in rows}),
            'firstEventP95Ms': sorted(r['firstMs'] - r['startedMs'] for r in rows)[18]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('receipt', type=Path)
    path = parser.parse_args().receipt
    with path.open('rb') as stream:
        body = stream.read(524289)
    require(len(body) <= 524288, 'Receipt exceeds limit')
    print(json.dumps(verify(json.loads(body))))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, TypeError, KeyError, OSError):
        raise SystemExit('Cutover receipt failed validation; inspect the local sanitized report.')
