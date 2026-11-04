#!/usr/bin/env python3
"""Generate or execute checksummed, serialized PostgreSQL migrations. Never used by app startup."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def migration_sql(root=ROOT, timescale=False):
    folders = [root / "deploy/migrations"]
    if timescale:
        folders.append(root / "deploy/timescale-migrations")
    migrations = []
    for folder in folders:
        files = sorted(folder.glob("V[0-9]*__*.sql"))
        versions = set()
        for file in files:
            version = re.fullmatch(r"V(\d+)__[a-z0-9_]+\.sql", file.name)
            if not version or int(version[1]) in versions:
                raise ValueError("Invalid or duplicate migration version: " + file.name)
            versions.add(int(version[1]))
        migrations.extend(sorted(files, key=lambda f: int(f.name[1:].split("__")[0])))
    out = ["\\set ON_ERROR_STOP on", "BEGIN;", "SET LOCAL lock_timeout='5s';", "SET LOCAL statement_timeout='120s';", "SELECT pg_advisory_xact_lock(682459104);",
           "CREATE TABLE IF NOT EXISTS control_schema_history (name TEXT PRIMARY KEY, checksum TEXT NOT NULL, applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);"]
    for index, file in enumerate(migrations):
        name = file.parent.name + "/" + file.name
        sql = file.read_text()
        checksum = hashlib.sha256(sql.encode()).hexdigest()
        out += [f"DO $$ BEGIN IF EXISTS (SELECT 1 FROM control_schema_history WHERE name='{name}' AND checksum<>'{checksum}') THEN RAISE EXCEPTION 'Migration checksum mismatch: {name}'; END IF; END $$;",
                f"SELECT NOT EXISTS (SELECT 1 FROM control_schema_history WHERE name='{name}') AS apply_{index} \\gset",
                f"\\if :apply_{index}", sql,
                f"INSERT INTO control_schema_history(name,checksum) VALUES('{name}','{checksum}');", "\\endif"]
    return "\n".join(out + ["COMMIT;", ""])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Apply to this project's timescaledb service; default prints SQL for review")
    parser.add_argument("--timescale", action="store_true", help="Also run explicit Timescale conversion/retention migrations")
    args = parser.parse_args()
    sql = migration_sql(timescale=args.timescale)
    if args.apply:
        prefix=["sudo","-n"] if os.environ.get("CONTROL_USAGE_DOCKER_SUDO")=="1" else []
        subprocess.run(prefix+["docker", "compose", "exec", "-T", "timescaledb", "psql", "-X", "-U", "meter", "-d", "meter"],
                       cwd=ROOT, input=sql, text=True, check=True)
    else:
        print(sql)


if __name__ == "__main__":
    main()
