# Quota observations

The dashboard shows the most recent trusted observation for each provider, upstream account and quota bucket. It does not poll provider accounts when an administrator refreshes the page. Historical report filters and member filters do not change the account-wide quota window.

An observation becomes stale when it is older than five minutes or its reported reset time has passed. The stored percentage remains a historical observation. A passed reset does not establish that current usage is zero; activity outside this gateway may have consumed allowance, and the provider may use overlapping windows. Missing observations are unknown, not zero.

## Session and weekly member breakdown

`GET /admin/quota-usage` is protected by the existing administrator session policy. The Usage & value page shows each account's latest trusted five-hour and seven-day observation beside gateway member activity. Report-period and member-ID filters do not redefine these windows. The provider filter applies; a local member-name/ID search hides rows without changing the all-member denominator.

The gateway interval starts at the reported reset minus five hours or seven days and ends at the earlier of the reset or report generation time, exclusive. Requests are assigned by start time, provider and upstream account. This is a reporting convention, not a claim about how the provider charges a request crossing a reset. Invalid intervals have no breakdown. Passed resets remain historical until a new trusted observation arrives; no new window or zero balance is synthesized.

The provider observation timestamp and the gateway reporting endpoint are shown separately. Gateway totals can include requests after the observation. Audit arrivals or partial streams can change the totals. Counts distinguish complete, partial and missing usage; missing usage is not treated as evidence of zero consumption. Token totals add input, output, cache creation and cache reads once. Percentages are rounded to two decimals and may not sum to exactly 100.

The subscription may be used outside the gateway. Outside usage and each member's full-allowance percentage therefore remain unknown (`allowanceUsedPercent: null`). Member percentages use only the reported gateway tokens for that account and window; they are not multiplied by account utilization. Unknown model weighting and incomplete records prevent exact allowance attribution even if all known gateway requests are recorded. Refresh performs no provider inference or quota polling.

## Current collection paths

- **Upstream response headers:** `AccountingService` processes retained completed requests and passes allowlisted Anthropic five-hour/seven-day utilization and reset fields to `QuotaSnapshots`. The observation time is the request's start plus the measured upstream-header delay, not the later projection time. Unsupported or invalid formats are ignored. No gateway traffic means this path cannot produce a newer observation.
- **Optional Coder status-line report:** `scripts/collect_claude_statusline.py` retains only recognized numeric quota windows from a status-line payload. `QuotaFileCollector` reads the explicitly configured `meter.quota-file` every minute. This is disabled when no path is configured. It preserves the report's observation timestamp, deduplicates unchanged reports, and rejects reports older than a day. Re-reading a file does not make it fresh. Keep the file operator-controlled and outside member-writable locations.

Neither path infers quota from token counts. No OpenAI quota collector is currently implemented. The file collector is not an API authentication mechanism or a background provider poller.

## Distinguishing inactivity from a collection problem

Compare the newest quota observation with the latest provider request, retained response-header timestamp and accounting assessment. Use counts and timestamps; do not print retained headers, request bodies or credentials for this check.

If the latest observation corresponds to the latest request, no later requests exist, and no finished requests await assessment, staleness is expected inactivity. Do not send billable inference solely to refresh the dashboard, extend timestamps, or reset percentages locally.

If newer requests have completed, check whether their retained response headers contain the recognized quota field names, whether capture/projection has caught up, and whether the accounting scheduler is progressing. Provider responses may legitimately omit these fields. A newer response without quota headers is not proof of a failed collector.

For an explicitly configured file source, check its existence, permissions, update time and numeric report timestamp. An idle terminal can stop producing status-line observations. Mount a dedicated report directory read-only into the gateway if the writer uses atomic file replacement; an individual file bind mount can retain the old inode.

The dashboard exposes the observation time, reported reset, age and source separately. Use the provider's own account usage view when a current account balance is required.
