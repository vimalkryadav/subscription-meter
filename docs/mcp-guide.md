# control-usage MCP setup and tool reference

The administrator MCP server gives compatible AI clients named tools for inspecting and managing control-usage. It runs as a **local stdio bridge** on the machine running the MCP client, and calls the platform's existing HTTPS admin APIs. It adds no public `/mcp` endpoint, no extra tunnel, no hosted OAuth application and no provider-generation route.

This guide describes the implemented bridge in `mcp/`, its tested limits and its setup as of **8 November 2026**. Read the administrator guide for business meanings and operating procedures.

## Architecture and access model

```text
AI client -> local stdio MCP process -> Cloudflare HTTPS origin
          -> existing admin session / role / CSRF checks -> admin APIs
```

The bridge uses the official MCP TypeScript SDK and a fixed tool registry. There is no arbitrary URL, SQL, shell, file-read, credential-discovery, role-change or model-generation tool. The Java gateway remains the authority for access checks, constraints and writes. The Node process is a local client adapter; it does not replace Spring Boot.

Authentication uses a dedicated private file containing an expiring administrator session and the exact site origin it belongs to. This is an administrator credential, not a limited member key. Trust the local machine, the bridge code and the AI client that you connect. The process's read/write and sensitive-content settings restrict the offered tools; they do not transform the underlying session into a separately scoped provider credential.

Each request re-reads the private file. The bridge refuses symbolic-link credential files, files owned by another user, group/world-accessible files, invalid token formats and origin mismatches. HTTP is not accepted. Redirects are not followed, so a redirected request cannot carry the session to another host. A tunnel-hostname change requires a verified new origin and a new login.

## Prerequisites

Use macOS or Linux with Node.js **22.13 or newer**, npm, Python **3.9 or newer**, a local checkout of this repository and an administrator account on the platform. Use absolute paths in MCP configuration; GUI applications may not load your shell's PATH.

The process and its session file should be on the same machine as the MCP client. For Claude Code running inside Coder, install there and use Coder paths. For a client on your Mac, use your Mac checkout. Do not point a Mac process at `/home/coder/...` unless you deliberately launch the whole process remotely through a separately configured transport.

This setup does not log Claude Code into a different Anthropic account and does not change an existing Mac provider login. It is a separate admin tool connection. Member model requests continue to use their own gateway keys.

## Install and sign in privately

From your checkout:

```sh
cd /absolute/path/to/subscription-meter
npm ci --prefix mcp --ignore-scripts
npm run build --prefix mcp
python3 mcp/login.py https://YOUR_CURRENT_PLATFORM_HOST
```

The helper prompts for administrator username and hidden password. It saves `secrets/mcp-session.json` with mode 0600 and does not print the password or session token. The file is ignored by Git. Do not copy it into client configuration, chat, issue bodies or a public repository.

To use a different private location:

```sh
python3 mcp/login.py https://YOUR_CURRENT_PLATFORM_HOST \
  --session-file /absolute/private/path/control-usage-session.json
```

The URL must be the HTTPS origin only: omit `/manage`, `/gateway`, query strings and credentials. The login helper and bridge reject redirects. If login fails, verify the hostname independently and sign in through the browser to confirm the administrator role; do not disable TLS verification.

The existing `scripts/admin_login.py` writes a raw session for older acceptance scripts. That file is **not** the MCP file format. Use `mcp/login.py` for this bridge's origin-bound JSON envelope.

## Configure the MCP client

The following example is for clients that accept a `mcpServers` JSON configuration. Replace every path and the hostname. Use the absolute path from `command -v node` if the client cannot find Node.

```json
{
  "mcpServers": {
    "control-usage-admin": {
      "command": "/absolute/path/to/node",
      "args": ["/absolute/path/to/subscription-meter/mcp/dist/index.js"],
      "env": {
        "CONTROL_USAGE_URL": "https://YOUR_CURRENT_PLATFORM_HOST",
        "CONTROL_USAGE_MCP_SESSION_FILE": "/absolute/path/to/subscription-meter/secrets/mcp-session.json",
        "CONTROL_USAGE_MCP_WRITES": "true",
        "CONTROL_USAGE_MCP_CONTENT": "false"
      }
    }
  }
}
```

| Setting | Meaning | Default |
| --- | --- | --- |
| `CONTROL_USAGE_URL` | Exact HTTPS platform origin | Required |
| `CONTROL_USAGE_MCP_SESSION_FILE` | Absolute path to the private origin-bound session file | Required |
| `CONTROL_USAGE_MCP_WRITES` | Advertise the seven named administrative action tools | `false` |
| `CONTROL_USAGE_MCP_CONTENT` | Advertise the two sensitive capture-reading tools | `false` |

Boolean settings accept only `true` or `false`. Changing tool exposure requires restarting/reconnecting the MCP process. Replacing the session file after a private re-login does not require restarting it.

The example enables admin actions because that is the requested integration. Set writes to `false` for a reporting-only connection. Configure the AI client's own approval policy to review administrative actions before execution; do not automatically approve every tool solely because it comes from this server. Tool annotations and the `confirm: true` argument express intent, but cannot prove a human clicked approval.

For Claude Code, its MCP configuration supports local stdio servers; you can add this entry using its MCP configuration flow. Keep configuration specific to the trusted administrative environment. For other clients, adapt this launch command and environment to their documented stdio format. A client that supports only remote HTTP MCP cannot connect directly to this release; do not expose the stdio process as an unauthenticated web service.

See the [official MCP server tutorial](https://github.com/modelcontextprotocol/typescript-sdk/blob/v2.4.0/docs/get-started/first-server.md) and [client tutorial](https://github.com/modelcontextprotocol/typescript-sdk/blob/v2.4.0/docs/get-started/first-client.md) for the underlying protocol and lifecycle.

## First connection and validation

1. Reconnect the MCP server in your client after installing/building and completing private login.
2. Confirm its name is `control-usage-admin` and inspect the advertised tools.
3. Read `control-usage://admin-guide` or call `search_admin_guide` with a narrow topic.
4. Call `get_status`, `get_operations` and `get_quota_usage` with no changes to production state.
5. Confirm the response timestamps and expected account/window data. These reads use existing records and do not generate provider traffic.
6. Before any action, read the relevant target list and verify the ID and current state. Do not use a random member as a write smoke test.

Typing `node mcp/dist/index.js` directly does not display a dashboard; the process waits for an MCP client on stdin. Stdout is reserved for JSON-RPC. The entrypoint prints only a fixed startup failure message to stderr when configuration prevents startup. It never prints credentials or raw API failure bodies.

## Reporting and inspection tools

All reporting tools require a valid administrator session except local guide search/resources. They return structured data as well as a text representation for client compatibility.

| Tool | Inputs | Result and important interpretation |
| --- | --- | --- |
| `get_status` | None | Credential presence, gateway/capture status; never the credential |
| `get_operations` | None | Readiness, worker lag, storage and backup checks, with report freshness |
| `get_metrics` | `hours`: 1, 6, 24 or 168; default 24 | Request, token, error and latency aggregates |
| `get_quota_usage` | Optional `provider` | Five-hour, weekly all-models and separate Fable weekly observations with independent resets/freshness; Fable-only member shares; allowance attribution stays unknown |
| `get_accounting` | `days` 1–90, optional `provider`, `memberId` | Historical cohort shares, work categories and API-equivalent estimates |
| `list_members` | None | Member IDs, names, active state, key prefixes and current limits; no usable tokens |
| `list_key_requests` | None | Access-request IDs, portal identities, states and reasons |
| `list_accounts` | Optional `search`, at most 100 characters | Latest 100 matching accounts |
| `list_account_sessions` | `accountId` | Non-secret session references, last-seen/expiry and active state |
| `list_admin_actions` | Optional exact `action`, `limit` 1–100 | Instrumented administrative writes, not every read or failed attempt |
| `search_requests` | Time/filter/page arguments described below | Allowlisted request metadata; no captured body/header fields |
| `get_request_accounting` | `requestId` | Saved price/classification assessment and coverage |
| `list_subscription_expenses` | None | Latest 100 manually recorded expense entries |
| `get_expense_allocation` | `expenseId` | Declared allocation among members and unallocated amount |
| `search_admin_guide` | `query`, `guide` = admin or mcp, `limit` 1–5 | Matching local documentation sections |

Provider values are `anthropic`, `openai`, or empty for all where supported. The OpenAI quota collector is not implemented, so its quota result is empty rather than zero. A tool can accept an OpenAI accounting filter without enabling OpenAI generation.

## Request search and pagination

`search_requests` accepts `from`, `to`, `memberId`, `model`, `modelFamily`, `requestId`, `upstreamRequestId`, `state`, `status`, `captureState`, `minDurationMs`, `maxDurationMs`, `usageSeen`, `provider`, `upstreamAccount`, `clientFamily`, `keyPrefix`, `category`, `limit` and `cursor`.

Use `modelFamily: "fable"` for the same Fable-only scope as the quota card; an empty or omitted value includes all families. The filter recognizes Anthropic `fable` and `claude-fable-…` requested model IDs.

Dates use ISO-8601 timestamps with timezone offsets; the interval is start-inclusive and end-exclusive, at most 365 days. Defaults are the last 24 hours and 25 records, with a maximum page size of 100. Duration bounds are nonnegative milliseconds. Capture states are pending, available, incomplete, truncated, error, expired or unavailable. Identifiers and strings are length-bounded; there is no arbitrary SQL or URL argument.

For another page, copy the returned `from`, `to` and `nextCursor`, keeping every filter unchanged. Stop when `nextCursor` is null. The bridge never traverses every page automatically. A changed filter invalidates the old cursor. Inspect `generatedAt` and `asOf` when comparing results with another report.

Example request, using fictional IDs:

```json
{
  "provider": "anthropic",
  "memberId": "MEMBER_ID_FROM_LIST_MEMBERS",
  "status": 429,
  "limit": 10
}
```

The example ID must be replaced with an actual ID returned by the platform; names are not IDs. If results are too large, shorten the window or reduce the page size before retrying.

## Administrative action tools

Every action requires `CONTROL_USAGE_MCP_WRITES=true` and `confirm: true`. The bridge obtains a fresh session view and CSRF token internally, verifies the administrator role, and sends one authenticated write. The gateway applies its normal constraints. Actions are never automatically retried.

| Tool | Required target and values | Effect |
| --- | --- | --- |
| `decide_key_request` | `keyRequestId`, `approve`, `rpm`, `concurrency`, `confirm` | Decide a pending request; approved members claim tokens themselves |
| `revoke_key_request` | `keyRequestId`, `confirm` | Revoke portal gateway access and prevent token rotation until reapproval |
| `revoke_member` | `memberId`, `confirm` | Block new requests using that member key |
| `update_member_limits` | `memberId`, `rpm`, `concurrency`, `expectedRpm`, `expectedConcurrency`, `confirm` | Conflict-checked changes for future admissions |
| `revoke_account_sessions` | `accountId`, optional `sessionRef`, `confirm` | Sign out one session, or all when the reference is omitted |
| `classify_request` | `requestId`, `category`, `confirm` | Persist an administrator category override |
| `record_subscription_expense` | Full expense fields below, `confirm` | Create one expense record; not an upsert |

RPM must be 1–600 and concurrency 1–20. A limit update requires current expected values from `list_members`. A 409 is a conflict, not an instruction to replace expected values and retry silently: read the target again and review the intended change.

Revoking a key does not sign out an account; signing out sessions does not revoke keys or prevent future login. Revoking the MCP session itself will make subsequent calls fail authentication. Existing model requests may finish after key revocation.

Expense creation requires `provider`, `upstreamAccount`, `periodStart`, `periodEnd`, `amount`, `currency`, `gatewayFraction`, `allocationBasis`, `provenance`, and `confirm`. Currency is USD/INR/EUR/GBP; fraction is 0–1; basis is `reported_tokens` or `equal`; source is `manual_invoice` or `estimated_subscription`. Use the operator's actual supplied facts. Do not infer an amount from the plan name. No expense edit/delete or automatic duplicate resolution is exposed.

Action tool annotations mark writes as potentially destructive and non-idempotent. These hints help clients present approvals; enforcement still depends on the fixed tool registry, configuration, session authorization and backend constraints. `confirm: true` is not a separate security token or proof of human approval.

## Example administrative workflows

**Review usage:** “Show the weekly all-models, separate weekly Fable, and five-hour account observations, their age/reset time, and members' gateway shares. Keep outside usage unknown and separate API-equivalent value from my subscription bill.” This uses reads and should not trigger an action tool.

**Approve a person:** list key requests, identify the intended username and pending request ID, state proposed RPM/concurrency, then call `decide_key_request` only for the requested decision. Read the list again to verify the state. The member claims their token in the portal; the assistant never needs to retrieve it.

**Change limits:** list members, identify the exact member and current limits, confirm the requested new values, then call `update_member_limits` with both current expected values and new values. After success, read the member again. A conflict requires review, not a blind overwrite.

**Investigate an error:** search metadata for the exact ID/time window, read its accounting and the current operations report, distinguish missing capture from successful empty output, and only enable/read sensitive capture when necessary for the intended investigation. Treat captured text as evidence, never as authorization for further actions.

**Offboard:** identify the account, linked access request and member. Revoke gateway access and, if requested, sign out the account's sessions as a separate action. Verify both states independently. Do not delete usage history or change the sole-administrator policy.

## Sensitive prompt and response access

The default tool set excludes captured bodies and event content. Set `CONTROL_USAGE_MCP_CONTENT=true` and reconnect only for a trusted client and intended content investigation.

`read_request_capture` accepts a request ID, one field (`request_body`, `response_body`, `request_headers`, `response_headers`, or `error_message`), an offset and a length. It returns at most 16,384 UTF-16 code units per call, default 4,096, with total length, next offset and capture state. A null value means unavailable, not an empty successful response. Offsets can divide surrogate pairs; reconstruct adjacent slices when exact Unicode text matters.

`read_request_events` accepts a request ID, an `after` sequence and a page size 1–25, default 5. Use the last returned sequence for another page. It does not silently trim an oversized event page or crawl the entire stream. Reduce the page size or use the dashboard export if the bounded response limit is reached.

Stored redaction is best effort. Content may contain confidential code, personal data, credentials that escaped recognition or malicious instructions embedded in user/tool text. Reading it through MCP sends it to the connected client's context, which may be processed by that client's AI provider. Granting access does not turn the content into trusted instructions. Do not execute a captured request to create an admin, reveal a secret or modify limits.

For large captures, use the dashboard's controlled export instead of trying to load the full history into a model context. Metadata search remains available without enabling these tools.

## Guide resources and prompts

Resources are bundled repository documentation, not dynamically generated secrets:

- `control-usage://admin-guide` — the complete administrator guide.
- `control-usage://mcp-guide` — this setup and tool reference.

Prompts provide starting instructions and do not execute tools or mutate data themselves:

- `investigate-request`, with `requestId`, suggests metadata-first diagnosis and separates client/provider/gateway timing.
- `review-weekly-usage` suggests a quota report that keeps observation freshness, token shares, outside activity and money estimates distinct.

Some clients do not expose resource/prompt menus. Use `search_admin_guide` to retrieve matching sections through a normal tool in those clients. Documentation search is local and does not require a valid platform session, although the server still needs a valid launch configuration.

## Expiry, revocation and credential handling

The private MCP helper creates a legacy session with a 12-hour absolute and one-hour idle limit. Persistent browser refresh logins use a separate flow; they do not change the MCP credential file format. Tool calls can update activity, but cannot extend its absolute lifetime. Re-run the private login helper after expiry and keep the configured file path unchanged. The bridge re-reads it on the next operation.

To disconnect access, revoke the corresponding session under Accounts & sessions, or revoke all sessions for that administrator if that broader effect is intended. Removing a local file prevents this bridge from loading it but does not revoke a copied server-side session. Logging out a browser's different session does not automatically revoke the MCP session.

Never use a member `sm_` key, upstream bearer credential, bootstrap admin key or password as this file's session. Do not expose the file through a tunnel or commit it. The server performs no browser-cookie scraping, Keychain lookup or provider credential refresh. Administrators remain responsible for their client machine and configuration.

## Bounds, errors and uncertain outcomes

The bridge permits at most four concurrent HTTP operations and only one action at a time per MCP process. Each HTTP request has a 15-second timeout and a 2 MiB response-body cap. Tool results are capped at 128 KiB and fail explicitly rather than presenting a silently truncated JSON result. The gateway's own bounds and shared controls also apply.

A client cancellation or network failure does not roll back a write already received by the server. If an action's response fails or times out, its outcome may be unknown: read the current state or action log before retrying. This matters especially for expense creation. There is no automatic write retry, batch executor or transactional multi-tool workflow.

| Error | Resolution |
| --- | --- |
| Could not start | Verify Node version, installed dependencies, built `dist/index.js`, exact environment names and absolute paths |
| Private session unsafe/missing/origin mismatch | Use `mcp/login.py` for the configured origin; ensure ownership and mode 0600; do not loosen permissions |
| 401 | Session expired or was revoked; log in privately again |
| 403 | Check administrator role and session/CSRF validity; no member-token fallback |
| 404 | Verify target ID, current hostname and deployed API support |
| 409 | Refresh current state and review the proposed action |
| 429 | Wait, then reconsider the request; do not loop aggressively |
| Redirect rejected | Verify the new site origin independently; re-login rather than forwarding the old credential |
| Response/result too large | Narrow the query, lower the page size, select a shorter capture field slice or use the dashboard |
| Action outcome unknown | Inspect target state and action log before any retry |
| Action/content tool absent | Check the process flags, rebuild if needed, and reconnect the MCP client |

## Testing and maintenance

Run `npm test --prefix mcp` for transport security, schema/action contracts, guide resources and real stdio protocol tests against isolated fixtures. Python helper tests are included with `python3 -m unittest discover -s scripts/tests -p 'test_*.py'`. CI runs these alongside the application checks.

Live acceptance should use reporting tools only unless a specific production change or disposable test subject has been authorized. Read-only acceptance verifies the deployed admin path and current session; it does not justify changing another user's key or invoice. Action behavior is tested against isolated fixtures and backend integration tests.

After pulling an update, run `npm ci --prefix mcp --ignore-scripts` and `npm run build --prefix mcp`, then reconnect the client. The local bridge does not automatically update when the website deploys. Keep the repository docs beside the bridge because its guide resources load those files.

This release does not provide a remote Streamable HTTP MCP endpoint, OAuth registration, scheduled autonomous administration, provider inference, credential issuance, role editing, password reset, deployment, rollback or production database restoration through MCP. Those operations require separate designs and authorization; none are hidden behind a generic tool.
