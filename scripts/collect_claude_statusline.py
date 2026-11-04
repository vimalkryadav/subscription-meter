#!/usr/bin/env python3
"""Claude statusLine adapter: retain only quota numbers, never stdin transcripts or credentials.

Configure this on the Coder account owning the upstream subscription. The gateway
collector trusts its operator-configured file; never point it at member-writable files.
Usage: collect_claude_statusline.py /private/runtime/claude-quota.json primary
"""
import datetime
import json
import math
import os
import re
from pathlib import Path
import sys
import tempfile


def main():
    destination = Path(sys.argv[1])
    account = sys.argv[2] if len(sys.argv) > 2 else 'primary'
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', account):
        return
    payload = sys.stdin.read(262145)
    if len(payload) > 262144:
        return
    root = json.loads(payload)
    if not isinstance(root, dict):
        return
    source = root.get('rate_limits', {})
    if not isinstance(source, dict):
        return
    windows = {}
    for name in ('five_hour', 'seven_day'):
        value = source.get(name, {})
        if not isinstance(value, dict):
            continue
        used, reset = value.get('used_percentage'), value.get('resets_at')
        if (type(used) in (int, float) and math.isfinite(used) and 0 <= used <= 100
                and type(reset) is int and reset > 0):
            windows[name] = {'used_percentage': used, 'resets_at': reset}
    if not windows:
        return  # Existing observation retains its original timestamp and becomes stale.
    document = {'upstreamAccount': account, 'observedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'rate_limits': windows}
    destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.quota-', dir=destination.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(document, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    print(' | '.join(f'{name}: {value["used_percentage"]:g}%' for name, value in windows.items()))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, IndexError, TypeError):
        pass  # Status-line failure must not echo stdin or interfere with the terminal.
