import { constants } from 'node:fs';
import { open } from 'node:fs/promises';
import { isAbsolute } from 'node:path';

export class SafeError extends Error {}
export interface Config { origin: string; sessionFile: string; writes: boolean; content: boolean }
export function origin(value: string): string {
  let url: URL;
  try { url = new URL(value); } catch { throw new SafeError('Set CONTROL_USAGE_URL to the HTTPS site origin, without /gateway or /manage.'); }
  if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash)
    throw new SafeError('CONTROL_USAGE_URL must be an HTTPS origin without credentials, path, query or fragment.');
  return url.origin;
}
export function config(env: NodeJS.ProcessEnv): Config {
  const sessionFile = env.CONTROL_USAGE_MCP_SESSION_FILE || '';
  if (!isAbsolute(sessionFile)) throw new SafeError('CONTROL_USAGE_MCP_SESSION_FILE must be an absolute path to a private session file.');
  for (const key of ['CONTROL_USAGE_MCP_WRITES', 'CONTROL_USAGE_MCP_CONTENT'])
    if (env[key] && !['true', 'false'].includes(env[key]!)) throw new SafeError(key + ' must be true or false.');
  return { origin: origin(env.CONTROL_USAGE_URL || ''), sessionFile,
    writes: env.CONTROL_USAGE_MCP_WRITES === 'true', content: env.CONTROL_USAGE_MCP_CONTENT === 'true' };
}

/** Credentials are re-read for each operation so private re-login needs no server restart. */
export async function session(config: Config): Promise<string> {
  try {
    const file = await open(config.sessionFile, constants.O_RDONLY | constants.O_NOFOLLOW);
    try {
      const stat = await file.stat();
      if (!stat.isFile() || stat.size > 8192 || stat.size === 0 || (stat.mode & 0o077) !== 0
          || !process.getuid || stat.uid !== process.getuid()) throw new Error('private file required');
      const value = JSON.parse(await file.readFile('utf8'));
      if (value.version !== 1 || value.origin !== config.origin || typeof value.session !== 'string'
          || !/^[A-Za-z0-9_-]{43}$/.test(value.session)) throw new Error('invalid session');
      return value.session;
    } finally { await file.close(); }
  } catch { throw new SafeError('Private MCP session missing, unsafe, invalid or bound to another origin. Run mcp/login.py for this site; keep the file owned by you with mode 0600.'); }
}

export interface AdminApi { request(method: 'GET' | 'POST' | 'PATCH' | 'DELETE', path: string, body?: unknown): Promise<unknown> }
export class HttpAdminApi implements AdminApi {
  private active = 0;
  private writing = false;
  constructor(private readonly settings: Config, private readonly fetcher: typeof fetch = fetch) {}

  async request(method: 'GET' | 'POST' | 'PATCH' | 'DELETE', path: string, body?: unknown): Promise<unknown> {
    // Tool handlers construct these paths; a model cannot supply arbitrary URLs.
    if (!path.startsWith('/admin/') || /[\s\\#]/.test(path) || path.split('?')[0].includes('..') || new URL(path, this.settings.origin).origin !== this.settings.origin)
      throw new SafeError('Unsupported admin route.');
    const write = method !== 'GET';
    if (write && !this.settings.writes) throw new SafeError('Admin actions are disabled for this MCP process.');
    if (this.active >= 4 || (write && this.writing)) throw new SafeError('Another operation is running. Wait for it to finish.');
    this.active++; if (write) this.writing = true;
    try {
      const credential = await session(this.settings);
      let csrf: string | undefined;
      if (write) {
        const current = await this.send('GET', '/portal/api/session', credential) as { user?: { role?: string }; csrfToken?: string };
        if (current.user?.role !== 'admin' || !current.csrfToken || !/^[a-f0-9]{64}$/.test(current.csrfToken))
          throw new SafeError('Administrator session required. Sign in again privately.');
        csrf = current.csrfToken;
      }
      return await this.send(method, path, credential, body, csrf);
    } finally { this.active--; if (write) this.writing = false; }
  }

  private async send(method: string, path: string, credential: string, body?: unknown, csrf?: string): Promise<unknown> {
    try {
      const response = await this.fetcher(this.settings.origin + path, {
        method, redirect: 'manual', signal: AbortSignal.timeout(15000),
        headers: { Cookie: 'meter_session=' + credential, Accept: 'application/json', 'X-Portal-Request': '1',
          'X-Control-Usage-Client': 'mcp', ...(csrf ? { 'X-CSRF-Token': csrf } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      if (!response.ok) {
        await response.body?.cancel();
        const messages: Record<number, string> = {
          400: 'Invalid values. Review the tool arguments.', 401: 'Admin session expired or revoked. Run mcp/login.py privately.',
          403: 'Administrator access or CSRF verification rejected.', 404: 'Record or route not found.',
          409: 'State changed. Read the current record before deciding again.', 429: 'Rate limited. Wait before another call.',
        };
        throw new SafeError(messages[response.status] || (response.status >= 300 && response.status < 400
          ? 'Redirect rejected. Verify the site origin; credentials were not forwarded.'
          : 'Gateway returned HTTP ' + response.status + '. For an action, its outcome may be unknown; inspect current state before retrying.'));
      }
      if (response.status === 204) return { saved: true };
      if (!response.headers.get('content-type')?.includes('application/json')) {
        await response.body?.cancel(); throw new SafeError('Expected JSON from the gateway. Check the site origin.');
      }
      const reader = response.body?.getReader();
      if (!reader) throw new SafeError('Empty gateway response.');
      const chunks: Uint8Array[] = []; let size = 0;
      try {
        while (true) {
          const chunk = await reader.read(); if (chunk.done) break;
          size += chunk.value.length;
          if (size > 2 * 1024 * 1024) throw new SafeError('Response exceeds 2 MiB. Narrow the query, reduce the page size, or use the dashboard capture export.');
          chunks.push(chunk.value);
        }
      } finally { await reader.cancel().catch(() => {}); }
      return JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch (error) {
      if (error instanceof SafeError) throw error;
      throw new SafeError(method === 'GET' ? 'Gateway request failed or timed out. Check connectivity and sign-in.'
        : 'Action outcome unknown after a network or response failure. Read current state before retrying; the action may have completed.');
    }
  }
}
