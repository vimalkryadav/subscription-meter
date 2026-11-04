export interface User { id: string; username: string; displayName: string; role: 'admin' | 'member' }
export interface Session { user: User; csrfToken: string }
let csrf = '';
export function setCsrf(value: string) { csrf = value; }
export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) { super(message); this.status = status; this.name = 'ApiError'; }
}
const messages: Record<number, string> = {
  400: 'Check the values and try again.',
  401: 'Sign in failed or your session expired. Please sign in again.',
  403: 'Access or request verification failed. Refresh and try again.',
  404: 'This record is no longer available.',
  409: 'This record changed. Refresh before trying again.',
  413: 'This request is too large.',
  429: 'Too many requests. Wait a minute before trying again.',
};
/** Same-origin transport; the session cookie remains HTTP-only. */
export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('\\') || /[\u0000-\u0020\u007f]/.test(path)) throw new Error('A same-origin API path is required.');
  const headers = new Headers(options.headers);
  headers.set('X-Portal-Request', '1');
  headers.set('X-CSRF-Token', csrf);
  if (options.body !== undefined) headers.set('Content-Type', 'application/json');
  const response = await fetch(path, { ...options, headers, credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) {
    if (response.status === 401) {
      csrf = '';
      if (!['/portal/api/login', '/portal/api/register', '/portal/api/session'].includes(path))
        window.dispatchEvent(new Event('control-usage:session-expired'));
    }
    throw new ApiError(response.status, path === '/portal/api/register' && response.status === 409
      ? 'That username is unavailable. Choose another.' : messages[response.status] ?? `Request failed (${response.status}). Try again.`);
  }
  if (response.status === 204) return undefined as T;
  const body = await response.text();
  return body ? JSON.parse(body) as T : undefined as T;
}
export async function getSession(signal?: AbortSignal): Promise<Session> {
  const session = await api<Session>('/portal/api/session', { signal });
  if (signal?.aborted) throw new DOMException('Request aborted', 'AbortError');
  setCsrf(session.csrfToken);
  return session;
}
