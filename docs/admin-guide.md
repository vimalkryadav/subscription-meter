# control-usage administrator guide

This guide explains how to operate the deployed platform, investigate requests, interpret usage, manage access and use its MCP integration. It describes implemented behavior as of **8 November 2026**. It contains no credentials. Treat live dashboard timestamps and deployment manifests as the source for current state.

## Quick start

1. Open the platform's current Cloudflare HTTPS origin. Sign in with an administrator account. The admin console is `/manage`.
2. Open **Operations**. Check the report time, gateway readiness, audit worker readiness, projection lag and disk headroom. An old green report is not evidence of current health.
3. Open **Access requests** to review pending users. Approve only the intended person, with appropriate requests-per-minute and concurrency limits.
4. Have the member claim their own token from the member portal. Send them the platform URL, not the upstream subscription credential.
5. Open **Connection** and use its Claude Code settings. The public model base URL ends in `/gateway`.
6. After a member sends a request, open **Request explorer**. Confirm their member ID, provider, response status, usage coverage and capture state.
7. Open **Usage & value → Session & weekly limits**. Read account usage and member gateway shares separately. Note when the provider reading was observed and when its reset occurs.
8. Read **MCP setup** in the guide selector before connecting an AI client. Admin actions are supported, but captured prompt/response access is separately enabled.

For routine checks, no inference request is necessary. Dashboard refreshes, the operations streaming probe and MCP reporting tools do not call Claude for generation.

## Platform map and responsibilities

The request path is member terminal → Cloudflare tunnel → Caddy edge → Spring Boot gateway → configured provider. The upstream credential stays on Coder. Member requests use individual platform keys, allowing attribution and admission limits.

The gateway writes request and audit records through the configured audit pipeline. The deployed Kafka worker projects them into PostgreSQL/TimescaleDB. Dashboard queries read those records. Prometheus collects operational metrics; it is not the accounting source of truth. Kafka projection can lag request completion, so request summaries, retained events and accounting assessments may become available at different times.

| Component | Responsibility | What it does not establish |
| --- | --- | --- |
| Member account | Portal login, access request, token claim | Registration alone does not permit generation |
| Member key | Gateway authentication and per-member attribution | It does not grant admin or MCP access |
| Admin session | Dashboard administration and authenticated admin APIs | It is not a model-request key |
| Upstream credential | Server-side provider authentication | It must not be distributed to members |
| TimescaleDB/PostgreSQL | Request history, captures, accounts, sessions and coordination | It cannot see requests sent outside the gateway |
| Kafka and worker | Acknowledged audit transport and asynchronous projection | A healthy broker alone does not mean the worker has caught up |
| Cloudflare and Caddy | Public HTTPS entry and route separation | The single workspace is not highly available infrastructure |
| MCP bridge | Named admin tools and guide resources for AI clients | It does not generate model requests or expose shell access |

The production workspace is `bugpolice-control`. Runtime files are under `/home/coder/subscription-meter`; automated releases use `/home/coder/control-usage-deploy`. Resources belonging to BugPolice are separate and must not be changed during control-usage maintenance.

## Authentication and administrator access

Sign in at the root URL. Administrators can open `/manage`; signed-out users are redirected to login, and ordinary member accounts cannot open the admin console. Every admin API checks access independently of what the browser displays.

Sessions use a host-only, Secure, HttpOnly, SameSite=Strict cookie. They expire after a 12-hour absolute lifetime or one hour of inactivity. Up to five sessions are retained per account. A dashboard refresh may update activity; it does not remove the absolute expiry. Logging out invalidates that session.

Authenticated mutations also require CSRF verification. The dashboard supplies this automatically. The MCP bridge obtains the CSRF token internally before an action; do not paste cookie or CSRF values into chat or terminal command arguments.

The deployed account policy preserves the sole administrator. The dashboard and MCP provide no role-promotion, password-retrieval, provider-credential-retrieval or self-service password-reset tool. Creating a new portal account always creates a member. The old bootstrap admin bearer credential stops granting access once an administrator account exists.

If the dashboard redirects unexpectedly, sign in again and reload it. A 403 usually means the account is not an administrator or a write failed verification; it is not a reason to share the owner's password with a member.

## Daily and weekly operating routine

**At the start of a day:** check Operations report freshness, readiness, worker lag, disk and backup age; review recent provider authentication failures; inspect pending access requests; inspect failed or incomplete requests in the last 24 hours.

**When onboarding a person:** verify the access request identity and purpose, choose limits, approve, have the member claim their token, then check one ordinary request under their member ID. Do not use a shared member key if individual attribution matters.

**During an incident:** record the time range, request ID, member ID, response status and capture status before changing settings. Compare client observations with gateway measurements. Use the fixed streaming probe to isolate the browser/tunnel path without using provider allowance.

**Weekly:** review both quota windows and observation ages; compare members within the weekly reset window; inspect missing usage records; review admin actions and active sessions; verify backup and restore-rehearsal freshness. Record actual subscription expenses only when their amount, currency, dates and allocation are known.

## Dashboard navigation

| Page | Use it for | First checks |
| --- | --- | --- |
| Metrics | Traffic, errors, tokens, timing distributions and top contributors | Window, mode, sample counts and usage coverage |
| Request explorer | Finding a request and examining metadata, capture and events | Time range, filters, request ID, capture state |
| Usage & value | Account quota observations, member shares, categories, value and expenses | Which denominator and time window each panel uses |
| Members & keys | Operator-managed keys, current limits and revocation | Correct member ID, active state and key prefix |
| Access requests | Portal approval, rejection and revocation | Intended account and current request state |
| Accounts & sessions | Account search, session sign-out and admin action log | Account ID versus member ID |
| Operations | Health, projection, storage, backups and streaming diagnostics | Report age before interpreting status |
| Connection | Member terminal settings and gateway capture/credential status | Current hostname and `/gateway` suffix |
| Admin guide & MCP | Searchable operating instructions, setup and tool reference | Choose the relevant guide and section |

Charts have tabular alternatives. App dropdowns support keyboard selection. Wide tables scroll horizontally on small screens. If a deployment happened while your tab was open, reload once to obtain the new frontend; an existing tab can continue using its earlier release assets.

## Onboard and approve members

A normal onboarding sequence is:

1. The person registers a portal account and signs in.
2. They submit **Request access** with a reason.
3. You find the pending item under **Access requests**, verify the username and display name, and choose RPM and concurrency.
4. Choose **Approve** or **Reject**. Only pending requests can be decided; a conflict means the state changed and should be refreshed.
5. An approved member uses **Get my auth token** in their own portal. The full platform token is shown once.
6. They set their terminal variables using **Connection** and keep their token private.

Typical states are `none`, `pending`, `approved`, `active`, `rejected` and `revoked`. Approved means the member may claim a token; active means a token has been issued. A rejected or revoked member can submit a new request. Approval is not a transfer of the upstream credential.

Do not assume the account ID, access-request ID and member ID are interchangeable. The account identifies the login; the access-request ID identifies its approval workflow; the member ID identifies gateway usage. MCP tools name these arguments separately.

## Keys, limits and offboarding

**Direct keys:** Members & keys also supports creating an operator-managed member key. Save or deliver the newly shown key privately; it cannot be retrieved from the list later. The list shows only a short prefix. MCP deliberately does not issue keys into an AI conversation: approve portal access and let the member claim their token instead.

**Rotation:** a portal member can rotate an active token. The old token stops working and the member ID is retained, preserving attribution across rotations. Revoked access blocks rotation until a new access request is approved.

**Limits:** RPM is requests per minute; concurrency is simultaneous admitted requests. Supported member values are RPM 1–600 and concurrency 1–20. The deployment also has a global 80-request concurrency cap. These are gateway admission controls, not Claude allowance allocations, reservations or guarantees. Increasing them does not increase the underlying subscription limit.

To edit limits, open **Edit limits** for the correct member, review the current values and save. The write checks the previous values to avoid silently overwriting another administrator's change. On a conflict, refresh and review the latest values. Limits apply to new admissions; they do not rotate keys, reactivate revoked members or terminate existing requests.

**Offboarding:** revoke the member's gateway access to block new model requests. In-flight requests may finish. If you also want to sign out portal sessions, do that separately under Accounts & sessions. Signing out sessions alone does not revoke a gateway key or prevent a later login. Preserve existing usage records for attribution rather than deleting history.

## Connect Claude Code

Use a terminal session dedicated to this gateway when an existing local provider login should remain untouched. Replace placeholders with the current platform origin and the member's own platform key:

```sh
export ANTHROPIC_BASE_URL="https://YOUR_HOSTNAME/gateway"
export ANTHROPIC_AUTH_TOKEN="YOUR_MEMBER_PLATFORM_KEY"
unset ANTHROPIC_API_KEY
claude
```

Keep the usable key out of screenshots, issue descriptions, shared shell histories and repository files. The admin login password, an MCP session and the provider subscription credential are not substitutes for the member key.

The public provider routes include messages, token counting and model listing under `/gateway`. A bare `/v1/messages` URL is not the configured public route. Additional CLI endpoints may need separate support. A valid platform key establishes gateway identity; successful provider generation still depends on the upstream credential, supported protocol and provider response.

Codex/OpenAI forwarding remains disabled. Creating the admin MCP integration does not enable Codex provider forwarding, change the owner's Mac login, or establish a hosted ChatGPT OAuth integration.

## Read Metrics correctly

Choose a supported window: one hour, six hours, 24 hours or seven days. The dashboard's default metrics view is 24 hours. Traffic and token charts describe recorded gateway activity in that window.

A p50 is a median, p95 the 95th percentile and p99 the 99th percentile of the measured sample. Always consider request count and model mix. A small sample or a changed workload can move percentiles sharply. Compare like windows, models and streaming/non-streaming behavior before attributing a difference to infrastructure.

**Header wait** measures time until upstream response headers. **First byte** measures first observed response bytes; these may be metadata rather than a generated text token. **Total duration** includes the provider response's lifetime and can be longer for long outputs. Client-observed time additionally includes client behavior, the public network, tunnel and edge. Origin timing alone cannot isolate Cloudflare latency.

An error rate is only as complete as the requests visible to the gateway. A browser failure before admission may have no request ledger entry. A successful HTTP status also does not establish complete retained content; inspect completion and capture state separately.

## Find requests and preserve an investigation

Request explorer supports a time range plus member, model, model family, request ID, upstream request ID, request state, HTTP status, capture state, minimum/maximum duration, usage presence, provider, upstream account, client family, key prefix and work category.

Start with a narrow time range and an exact request ID when possible. Add member or provider filters if the ID is unknown. A model filter is an exact value, not a general description such as “fast model.” Duration filters are milliseconds.

Search uses cursor pagination, newest first. Continue with the returned cursor and unchanged filters/time bounds. A cursor from another search is rejected. The API supports 1–100 rows per page and intervals up to 365 days; the UI uses smaller pages. Shareable search URLs contain filter metadata, not authentication. Recipients still need administrator access. Consider whether member identifiers in a shared URL are appropriate for the audience.

Clicking a member in a quota window opens requests for that member, provider, upstream account and reset-aligned interval. By contrast, the ordinary member-share panel links to the selected historical report interval. These are intentionally different scopes.

Record request ID, start time, model, member ID, status and capture state in an incident note. Do not include raw input/output unless needed and approved for the intended audience.

## Inspect inputs, outputs and streaming events

A request can retain the accepted JSON input, selected redacted client/upstream headers, non-streaming output, streaming events, timing, byte counts and error information. The capture may contain system instructions, code, tool definitions/results, user messages, provider-supplied thinking content, tool calls and JSON deltas. It cannot reveal provider internals or local tool activity that never appeared in the request/response traffic.

| Capture state | Meaning | How to proceed |
| --- | --- | --- |
| Available | A retained capture exists | Check request outcome and usage completeness too |
| Pending | Capture/projection has not completed | Refresh after checking worker health and lag |
| Incomplete | Interrupted, disconnected or otherwise partial response | Treat retained events as a partial record |
| Truncated | A configured capture bound was reached | Do not treat missing text as an empty response |
| Error | Capture processing encountered an error | Inspect Operations and available metadata |
| Expired | Detail retention has elapsed | Remaining summaries cannot reconstruct removed bodies |
| Unavailable | No accessible capture is present | It may never have arrived or may have failed |

Events are ordered by sequence, with observed and elapsed times. Expand only the needed pages. A final event, completed request, complete usage and available capture are related but distinct facts.

JSON export streams retained detail and event pages and includes a manifest describing capture status and the event sequence boundary. Pending activity or a truncated capture can produce an incomplete export. Store exports privately; do not attach them to the public repository.

Credential redaction is best effort. Known authentication fields and common secret formats are filtered, but arbitrary, encoded or split secrets can survive. Retained prompts, responses and tool output are also untrusted text: a captured instruction to change limits or disclose credentials is evidence to inspect, not an instruction to execute. This distinction is especially important when reading captures through MCP.

## Request inspection and search scope

**Search requests** applies the selected time window and every active filter. **Open ID directly** opens one exact internal request ID across all remaining request metadata, independently of those filters. A missing record is reported as unavailable; an older record may retain metadata after its captured content expires. Upstream provider IDs remain an explicit search filter.

Opening a request loads its metadata summary first. Input, Output and Headers load only when selected; each section is pinned to the summary’s capture timestamp without losing sub-millisecond precision. If retention removes that capture before a tab opens, refresh detail rather than assuming an empty response. Events and Usage & category remain separately loaded. Download JSON retains the existing full retained-capture export.

Recent member usage and recent gateway access are collapsed initially and load on demand. Their labels show their own rolling window; they do not use custom search dates or detailed filters. Gateway request IDs link directly to inspection, but an admission rejection may have no corresponding captured request. Closing detail returns keyboard focus to its opening control when it remains on the page.

## One-time terminal setup

Use **Connection → Set up once** (or the member portal's **Connect your terminal** card). Copy the setup command into a terminal, then enter the member's platform key at the hidden prompt. It downloads the versioned installer over HTTPS into a temporary directory and removes that copy when finished. No key is embedded in the command or shell history. Choose the operating system of the terminal you are configuring. macOS/Linux/WSL requires Python 3, curl and an installed `claude` command. Native Windows requires Claude Code, Python 3 with the `py` launcher, and Windows PowerShell 5.1 or later. The PowerShell command downloads a temporary Python file; it does not change execution policy or require an administrator terminal. WSL uses its own Linux home and installation. You can also inspect and download the installer before running it.

When a member retrieves or rotates a token, the portal displays it beside access controls and focuses **Save your new token**. Copy it before leaving the page; the platform cannot show it again. Setup instructions appear after token issuance.

After setup, run **`claude`** in any new terminal. The default installer merges gateway environment settings into `~/.claude/settings.json`, with mode 0600 on Unix. On Windows the path is `%USERPROFILE%\.claude\settings.json`; the setup directories, settings and backup use protected ACLs granting only your user and SYSTEM access. Redirected paths (including junctions), files owned by another user and filesystems that cannot enforce private ACLs are rejected. Existing hooks, permissions, model preferences and other settings remain. Conflicting API-key, OAuth environment and alternate-provider variables are cleared in settings while the gateway is installed. Saved provider login files and shell startup files are untouched; the gateway token takes precedence over a saved personal login.

The first pre-install settings are backed up privately in `~/.config/control-usage/direct-setup-backup.json` (0600 on Unix, user/SYSTEM-only ACL on Windows). Re-running setup for key rotation or a new tunnel hostname preserves that original backup. Both settings and backup contain private plaintext credentials: do not share or commit them. Configure each machine and OS user separately.

**Undo setup or update your key** contains a copyable undo command (`--remove`). Undo restores prior managed authentication/gateway values only where they still match installer values. It preserves later edits, unrelated settings and conversation history, then deletes the private setup backup. Concurrent installer runs are serialized. If `CLAUDE_CONFIG_DIR` is set, default setup stops rather than silently configuring a different profile: unset it, or on Unix choose the separate-profile option. Project and managed settings can override user settings; verify the effective configuration with `/status`.

**Optional: keep personal and work profiles separate** is available on macOS/Linux/WSL only and uses `--separate-profile` and installs `~/.local/bin/claude-work`. This retains the earlier behavior: a private dedicated profile at `~/.config/control-usage/claude/settings.json` and a launcher that clears conflicting inherited provider/authentication variables. Use this option when ordinary `claude` should retain personal settings. Undo default setup first if already installed. Remove the optional profile's gateway credentials and owned launcher with `--separate-profile --remove`; history remains. Existing users of the earlier installer should use this separate-profile removal option.

All terminals reuse the same member key and share its limits. Different conversations do not need new keys. After rotation or a tunnel hostname change, rerun setup and restart active sessions. Uninstalling does not revoke the server-side key; revoke access separately if necessary. Temporary shell exports remain available for one-off testing. This installer targets the terminal CLI, including native Windows; separate editor/desktop authentication flows need their own configuration. Windows CI exercises setup, rotation, undo, concurrent installs, Unicode/BOM settings, ACLs and junction rejection in temporary homes. It does not perform an interactive Claude login or a billed model call on a user’s Windows device. See [Claude Code gateway configuration](https://code.claude.com/docs/en/llm-gateway-connect) for those surfaces.

## Session and weekly limits

**Allowance** is the provider's usage limit included in the subscription. The dashboard tracks three separate buckets: **Five-hour session**, **Weekly — all models** and **Weekly — Fable**. The all-models weekly bucket is the normal account-wide limit; the Fable bucket is a separate model-specific limit. It does not assume the Pro plan has a fixed token capacity.

The Session & weekly limits cards show the last reported account utilization, provider observation time, reported reset, source and freshness. A reading older than five minutes is stale; a passed reset is labeled historical with current usage unknown. Refresh reloads recorded data and does not poll the provider or send an inference request. There are currently trusted collectors for supported upstream utilization headers and an optional Coder status-line file.

Each bucket has its own percentage, reset and freshness. Do not add the two weekly percentages or subtract Fable from the all-models limit. A missing Fable observation stays Unknown, with no borrowed reset or invented member window. Fable readings may be absent when the retained traffic uses other models.

The Fable member breakdown counts only Anthropic requests whose requested model is `fable` or begins `claude-fable-` (case-insensitive). Other or unrecognized model IDs are excluded. Its member links preserve a **Fable only** model-family filter in Request explorer. The all-models breakdown includes all recorded models, including Fable; the two breakdowns are overlapping views, not additive costs or quotas.

The member breakdown derives its start from the reported reset minus five hours or seven days. Its end is the earlier of report generation and reset, exclusive. Requests are assigned by their start time. This convention does not establish how the provider accounts for a request that crosses a reset. Invalid intervals have no member breakdown.

The account observation and gateway totals have different timestamps. Gateway totals may include newer requests than the provider reading. Complete, partial and missing usage counts make this visible; later audit processing may revise partial data. A provider reading of 0% can coexist with recorded tokens and is not proof that requests were free or consumed no allowance.

The subscription may be used outside control-usage. Therefore outside usage and each member's percentage of the full allowance remain **Unknown**. A member's **gateway token share** is their reported tokens divided by all recorded members' reported tokens for that account and quota window. Token totals include input, output, cache creation and cache reads once. No weight is invented to imitate the provider's quota calculation.

Example only: if the provider reports 60% account utilization and Alice has 25% of gateway tokens, do not claim Alice used 15% of the subscription. Outside activity, different model weighting and incomplete observations prevent that conclusion.

The member-name/ID search in these cards hides rows without changing the all-member denominator. The report-period and member-ID controls above the cards do not redefine quota windows. The provider filter does apply. An unsupported provider has no quota data; it is not shown as zero.

## Historical shares, value and work categories

The general Usage & value report supports the last 1, 7, 30 or 90 days, provider and member-ID filters. Its **Observed member share** uses the selected cohort as denominator. Selecting one member can therefore show 100% of that selected cohort. The quota-card search works differently and preserves all-member shares.

**Known API-equivalent value** estimates what observed usage would cost under a recorded API pricing version. It is not the subscription invoice, an incremental provider charge, a guaranteed saving or an exact quota measure. Pricing coverage distinguishes priced, partly priced and unpriced requests. Missing components are excluded from known subtotals; absence of price or usage does not imply zero cost. Detail preserves the original assessment's price version rather than silently repricing history.

Work categories include implementation, debugging, testing, refactoring, review, documentation, research, planning, operations, mixed and unknown. The local heuristic examines bounded eligible user content; uncertain or unavailable content can remain unknown. Administrators can override a request's category. Category labels describe apparent work type, not productivity, effort, value or the person's job role. Use request inspection to assess a suggestion before overriding it.

## Record subscription expenses

Use **Subscription expense allocation** only when you have the required facts. Enter provider, upstream account alias, period start and end, amount, currency, gateway allocation percentage, allocation basis and amount source.

Supported currencies are USD, INR, EUR and GBP. Use the paid amount from the invoice when available and choose **Manually recorded invoice**. If the amount is an estimate, select the estimate source explicitly. Do not guess a price simply because the account is called Pro. Billing-period dates and taxes/currency can differ.

The gateway percentage is an administrator-declared expense allocation, not an observed fraction of Claude's allowance. Leave outside activity unallocated. Allocation can use recorded token share or equal shares among members with recorded requests. Missing usage affects confidence in token-based allocation. Review the returned coverage and unallocated amount.

The end date is exclusive and must follow the start by no more than 366 days. Amounts have at most two decimal places and are bounded at 1,000,000. The API uses `gatewayFraction` from 0 to 1; the UI asks for percent from 0 to 100. For example, 40% is 0.4 in MCP/API arguments.

Creation is not an idempotent upsert. If an expense submission times out, inspect the expense list before resubmitting; the first write may have succeeded. No edit/delete expense action is exposed in the dashboard or MCP. Verify the intended period and amount before creating a record.

## Accounts, sessions and action records

Search accounts by username or display name; the directory returns the latest 100 matches. Account role and gateway-access state are separate. A person can be signed in but have no approved key, or possess an active key after being signed out.

Session lists show last-seen time, expiry, active/idle state and a non-secret reference. Sign out one session or all sessions for the chosen account. The user can sign in again. If you revoke the current admin/MCP session, its next operation will require re-login.

The administrative action list contains instrumented writes, not every dashboard read, provider call or failed attempt. It includes member creation/revocation, access approval/rejection/revocation, limit changes, session revocations, category overrides and subscription expense creation. New approval/key records carry the verified actor and a diagnostic client label; that label is not authentication evidence. Older operations are not backfilled into a fabricated history.

Useful exact action filters include `member.create`, `member.revoke`, `key_request.approve`, `key_request.reject`, `key_request.revoke`, `member.limits.update`, `sessions.revoke`, `sessions.revoke_all`, `classification.override` and `subscription_expense.create`. Record metadata excludes usable keys, cookies and provider credentials.

## Operations and latency diagnosis

Operations reads a sanitized report generated on Coder every minute. Reports older than three minutes, or implausibly in the future, become unavailable. Unknown does not mean healthy. Checks cover gateway readiness, metrics collection, worker readiness and lag, the audit admission guard, recent upstream authentication responses, disk headroom and backup/restore age. Three consecutive failures trigger an alert state. The hourly review is shown separately in Operations; it can perform one verified committed-history cleanup for Kafka log pressure, then recheck health. Missing/stale reports and unfamiliar failures require attention. It does not generate patches, deploy untested code, restart shared services or clear unresolved audit failures. Notifications currently go to the local journal, not email or Slack.

For slow requests:

1. Identify whether the client reached the gateway: search by request ID, member and time. If absent, inspect the client URL, network and protocol path first.
2. Compare upstream header wait, first-byte and total duration. A long generated response naturally increases total duration.
3. Compare client-observed timing with gateway timing for the same request. Do not subtract unrelated samples to claim tunnel latency.
4. Check audit lag, disk pressure and readiness. In acknowledged Kafka mode, audit publication occurs before response frames are released.
5. Run **Test browser streaming** if the browser/tunnel path is in doubt. It sends 16 fixed events over roughly three seconds. **Test 35-second stream** sends 71 fixed events for deployment observation. Neither calls a provider, creates model-usage records or initiates a deployment.
6. A diagnostic pass proves that tested path and moment only. It does not prove provider generation, eliminate all buffering or guarantee 20-user reliability.

The earlier browser HTTP/2 failure experiment and the successful HTTP/1.1 fallback are separate evidence. Do not claim all 20 original browser streams succeeded. See the repository's streaming diagnostics for detailed historical tests and their limits.

## Deployment and availability

Normal releases flow from reviewed `main` through GitHub checks, an immutable image and the Coder blue/green deployer. The candidate must become ready; the edge switches; previous requests drain. Sessions, admission counters and database state are shared. Release asset fingerprints allow existing browser tabs to retain coherent code until reloaded.

For operations, inspect the active release and readiness through approved deployment tooling. Follow [the blue/green procedure](https://github.com/vimalkryadav/subscription-meter/blob/main/deploy/BLUE_GREEN.md). Do not use an old single-container `docker compose ... --force-recreate app` instruction as a normal upgrade procedure on the blue/green deployment. Do not recreate shared edge, database, broker, worker or tunnel resources as part of a routine app release.

A workspace, disk, database, broker or tunnel outage can still stop the service. Blue/green reduces application-release disruption; it is not a second independent machine or a guarantee of zero downtime for every failure. Coder shutdown policies still apply.

The zero-budget public entry uses a Cloudflare Quick Tunnel with a generated hostname. That hostname may change when the tunnel is recreated. There is no purchased stable domain for this project. Quick Tunnel behavior and limitations must be considered separately from successful local stream tests. Never change BugPolice's domain or tunnel to repair this service.

## Retention, backups and recovery

The configured detail retention is shown under Connection; the deployment's default is 30 days. Request summaries may outlive detailed captures. Expired content cannot be reconstructed from token counts. Retention deletion in the active database does not delete data already retained in backups.

Daily backups run at 01:15 UTC with jitter; a weekly restore rehearsal runs on Sundays at 02:15 UTC. The backup uses a consistent database snapshot, checksum and inventory. The isolated restore test compares recorded counts and schema/extension facts using the recorded database image. Operational reports expose freshness and success, not raw contents.

Backups are local, unencrypted files protected by filesystem permissions under `/home/coder/control-usage-backups`. They contain sensitive captures and account/session hashes. They do not protect against loss of the entire workspace or disk. There is no configured off-host copy. Credentials, deployment images and accepted Kafka records not yet projected are not part of a logical database dump; preserve the broker state when planning recovery.

Authorized maintenance can inspect the timers or run an isolated verify operation from the deployment checkout:

```sh
cd /home/coder/control-usage-deploy
systemctl list-timers 'control-usage-*'
CONTROL_USAGE_DOCKER_SUDO=1 python3 scripts/backup_database.py --verify-latest
```

`--verify-latest` rehearses restoration into an isolated temporary database; it does not restore over production. Actual disaster recovery needs a reviewed target, compatible application/event versions, broker-backlog handling and an explicit cutover procedure. Do not delete failed backups or unrelated files to make a restore report green. See [operations and backup details](https://github.com/vimalkryadav/subscription-meter/blob/main/docs/operations.md).

## Troubleshooting reference

| Symptom | Checks and next action |
| --- | --- |
| Public URL is not found | Verify the current tunnel hostname and exact path. `/manage` is the admin page; model clients use `/gateway` |
| Redirect to login | Session may be expired/revoked. Sign in at the same origin; do not reuse a cookie across hosts |
| 403 on admin page/API | Confirm administrator role; for writes, use the dashboard/MCP CSRF flow |
| Metrics empty | Check time range, filters, request arrival, projection lag and errors; do not seed mock data |
| Quota stale or unknown | Compare observation time with recent traffic. Refresh cannot force provider data; do not infer zero after reset |
| Member appears as 100% | Determine whether this is a selected historical cohort or the all-member quota-window share |
| Tokens recorded while quota says 0% | Account observations and token accounting differ; check timestamps and provider reporting precision |
| Capture pending | Check worker readiness/lag; refresh when projection catches up |
| Capture expired/truncated | Inspect available metadata and manifest. Missing text cannot be recreated from totals |
| 401/403 from upstream | Confirm the error is upstream, inspect credential presence and expiry via private operator procedures; never paste the credential |
| 429 | Distinguish member/global admission limits from provider throttling. Raising gateway limits does not raise the subscription allowance |
| Limit update conflicts | Refresh members, review current limits and resubmit only the intended change |
| Expense action times out | Read the expense list before retrying to avoid duplicates |
| Streaming probe fails | Inspect browser/network/protocol/tunnel behavior; a provider credential change is not a remedy for a pre-provider probe |
| Operations unavailable | Check report time and collector/workspace health. Do not treat a stale success as current |
| New feature absent after deployment | Reload the tab to load the current frontend bundle |
| MCP reports expired session | Run its private login helper again; do not put credentials in tool arguments |
| MCP output is too large | Narrow the window/filter, reduce the page, select a bounded capture slice or use dashboard export |

## Glossary and boundaries

**Account**: portal login identity. **Member**: gateway attribution identity. **Access request**: approval workflow connecting them. **Provider account**: upstream subscription alias, such as `primary`.

**Allowance**: provider usage limit. **Quota observation**: a timestamped provider report about that account. **Gateway share**: a member's share of recorded tokens for a stated population/window. **API-equivalent value**: a pricing-based estimate. **Subscription allocation**: an explicit bookkeeping decision about an actual or estimated expense.

**Usage complete** means the necessary usage components were observed; **capture available** means retained detail is accessible. Neither phrase alone proves an entire client conversation, local tool execution or provider-internal reasoning was recorded.

**Admin MCP** means tools for operating this platform from an AI client. It is distinct from Claude Code's model connection through `/gateway`, and distinct from the disabled Codex provider adapter. The MCP guide documents its credentials, tools, action semantics and limitations.
