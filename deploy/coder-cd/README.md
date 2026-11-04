# Scoped automatic deployment through Coder

Successful pushes to `main` run the release checks, publish the image, and pass the exact commit and GHCR digest to the fixed receiver in `bugpolice-control`. Manual workflow dispatch deploys only with `deploy=true`. The production environment accepts only the `main` branch. Workflow concurrency serializes releases; the receiver also holds a host lock, verifies main ancestry and requires the completed bootstrap marker. Images are public, so the host needs no registry credential.

The production environment secret `CODER_SESSION_TOKEN` is an expiring Coder automation token with exactly:

- Scopes: `workspace:read`, `workspace:ssh`, `template:read`.
- Allowed workspace: `6b76a990-b67e-44bd-a37e-ab2fa6c66650` (`bugpolice-control`).
- Allowed template: `f979d0f7-f4a2-4ac7-a1ac-2312bf289724`.

This grants SSH as the workspace user inside that workspace. It is not a forced-command server credential: a compromised authorized production workflow could use its workspace SSH capability. Keep production secrets out of pull-request workflows, review workflow changes, and rotate or revoke the token promptly. It grants no access to unrelated workspaces. The wrapper accepts only the fixed receiver, a full commit SHA and a canonical control-usage image digest. It does not start stopped workspaces.

## Coder v2.38.4 compatibility

The exact allowlist triggers `get templates: pq: column reference "id" is ambiguous` when Coder's workspace GET includes template details. The supported `include_related=latest_build.*` query returns the required workspace and agent data without that faulty template lookup. The wrapper changes only that exact workspace GET; server authorization and token restrictions still apply. See the official [workspace handler](https://github.com/coder/coder/blob/v2.38.4/coderd/workspaces.go), [SSH CLI](https://github.com/coder/coder/blob/v2.38.4/cli/ssh.go), and [workspace SDK](https://github.com/coder/coder/blob/v2.38.4/codersdk/workspacesdk/workspacesdk.go).

The stock CLI is downloaded over HTTPS from this deployment's public binary endpoint. Its temporary configuration is isolated, all inherited `CODER_*` variables are removed, and the scoped token is supplied through a private regular file and child environment. A loopback proxy has a fixed HTTPS upstream and requires the token on API calls. DERP relay endpoints are public encrypted transport endpoints; the proxy preserves both standard upgrade and fast-start raw frames rather than parsing them as HTTP responses. No API bodies or credentials are logged. No new remote daemon, listening port, or personal credential is installed.

The workspace watch endpoint can still emit a server-side template error which the stock SDK ignores; automatic-stop/event notifications are therefore not reliable under this workaround. SSH was verified with the exact allowlist using a short-lived token and the read-only remote command `true`; that probe token was revoked. Remove this workaround only after testing a Coder server fix with an identically scoped token.

## Rotation and failure handling

Create a replacement token with a 30-day lifetime and the exact scopes/allowlist above using the authorized operator CLI. Write it directly to a private temporary file, test with `go run deploy/coder-cd/main.go --coder /path/to/matching/coder --token-file /private/file --probe`, then install it using `gh secret set CODER_SESSION_TOKEN --env production < /private/file`. Revoke the previous token after successful replacement and delete the temporary file. If setup fails, revoke the new token. Record only token ID/name and expiry in operational receipts; never commit token values.

The wrapper bounds an SSH attempt to 15 minutes and the job to 20 minutes. A timeout or dropped SSH connection does not prove the remote deployment stopped. Inspect the deployment lock, `deploy/state` and active/readiness endpoints before retrying; do not start a second deployment or reverse database migrations blindly. Existing streams drain on the old slot during ordinary releases. Single-host failure, a stopped workspace, an expired token, or a stopped Quick Tunnel still prevents availability; this is deployment continuity, not host redundancy.

Run transport tests with:

```sh
go test -race deploy/coder-cd/main.go deploy/coder-cd/main_test.go
```
