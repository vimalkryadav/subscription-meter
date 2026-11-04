# Operational checks and database backups

The admin **Operations** page reads a sanitized report produced every minute on
Coder. The report is unavailable if the collector stops updating; old success is
never presented as current health. Checks cover gateway and audit readiness,
projection lag, recent upstream authentication failures, disk headroom, and backup
and restore age. Sustained failures and recoveries are written to the system
journal. No email, Slack, or other external notification destination is configured.
This monitor runs on the same workspace: a total workspace outage requires an
external observer to detect it.

## Browser streaming test

Use **Test browser streaming** to check event delivery through your current public
URL without making a model request. This complements local health checks and
reports incremental, batched or buffered delivery. See [streaming diagnostics](streaming-diagnostics.md)
for the measured protocol comparison and its limits.

## Backups

`control-usage-backup.timer` runs daily at 01:15 UTC, with up to five minutes of
jitter. `control-usage-restore-check.timer` verifies the newest completed archive
on Sundays at 02:15 UTC. Both timers catch up after downtime and serialize through
a private file lock. The restore drill is limited to 768 MiB RAM and half a CPU.

Archives stay in `/home/coder/control-usage-backups`, outside the repository and
public assets. The directory is mode 0700 and archives/manifests are mode 0600.
Backups contain sensitive captured content, account password hashes and session
hashes. They must never be committed, served, or attached to an issue. They are
local, unencrypted copies protected by filesystem permissions; these copies do
not protect against disk loss, workspace loss, or an operator with host access.
No extra infrastructure or off-host storage has been provisioned.

The backup and its row-count inventory share one exported, read-only PostgreSQL
snapshot. A completed custom-format archive receives a SHA-256 checksum and the
exact database image ID. Source tables, credentials, admin roles and active
requests are not changed. The latest seven completed archives and the latest
successfully restore-tested archive are retained. This can retain captured data
beyond its active-database retention period; do not treat database chunk deletion
as deletion from backups. A failed restore preserves the previous verified copy.

Storage limits fail closed: 512 MiB per archive, 4 GiB total archive-directory
budget, source database at most 2 GiB, and at least 4 GiB free filesystem space
with additional room based on database size. Crossing a limit produces an alert;
it does not delete unverified or unrelated operator files to make room. Increase
these reviewed limits before sustained volume outgrows this prototype.

A restore drill validates the checksum, starts the recorded local image with no
network, no published ports and no live volumes, uses Timescale's pre/post-restore
functions, and compares all recorded row counts, sole-admin count, hypertable
count and extension version. It removes only its own temporary container and
anonymous data volume. Raw database stderr is suppressed because it may contain
captured content; operational status contains only fixed fields and timestamps.

```sh
cd /home/coder/control-usage-deploy
CONTROL_USAGE_DOCKER_SUDO=1 python3 scripts/backup_database.py --verify
CONTROL_USAGE_DOCKER_SUDO=1 python3 scripts/backup_database.py --verify-latest
systemctl list-timers 'control-usage-*'
journalctl -u control-usage-backup.service -u control-usage-restore-check.service
```

`--verify` creates a backup then tests it. `--verify-latest` tests the newest
completed backup without changing the source. There is deliberately no command
that restores over production. Disaster recovery requires a reviewed target,
current application/event compatibility, and explicit cutover planning. Database
backups do not include provider credentials, Docker images, the Coder automation
credential, or accepted Kafka records not yet projected to PostgreSQL. Preserve
the broker volume and replay backlog when recovering an audit-consistent state.

Timescale backup procedure: [logical backup and restore](https://github.com/timescale/Tiger-Data-Docs/blob/main/src/content/docs/deploy/self-hosted/backup-and-restore/logical-backup.mdx).

## Installing on the dedicated workspace

Create `deploy/operations` in the deployment checkout with mode0755 before the
first application release that mounts it. Only sanitized JSON reports belong
there; archives live in their private directory. After the reviewed code is on
main and the release checkout has updated:

```sh
cd /home/coder/control-usage-deploy
mkdir -p deploy/operations
chmod 0755 deploy/operations
sudo install -m 0644 deploy/control-usage-backup.service deploy/control-usage-backup.timer deploy/control-usage-restore-check.service deploy/control-usage-restore-check.timer deploy/control-usage-monitor.service deploy/control-usage-monitor.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now control-usage-backup.timer control-usage-restore-check.timer control-usage-monitor.timer
```

The first reviewed backup plus isolated restore was exercised on Coder twice,
including Docker-filesystem capacity checks and the in-container dump timeout.
Both passed with matching snapshot inventories. Rehearsal containers were removed.
This verifies recovery into an isolated database, not a destructive production
restore or a whole-workspace disaster recovery exercise.
