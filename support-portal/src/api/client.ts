import type { ApiError } from './types';

// The one HandOffly backend. Empty = same host (the dev server proxies /api); set VITE_API_BASE_URL for a deployed API.
const BASE = import.meta.env.VITE_API_BASE_URL ?? '';
// Not the customer app's key, so the two apps never read each other's session even on one domain.
const TOKEN_KEY = 'handoffly.support.token';

let authToken: string | null = localStorage.getItem(TOKEN_KEY);
let sessionEnded: (() => void) | undefined;

export function setToken(token: string | null) {
  authToken = token;
  if (token) localStorage.setItem(TOKEN_KEY, token);
  else localStorage.removeItem(TOKEN_KEY);
}

export function getToken(): string | null {
  return authToken;
}

/** Called when the backend stops accepting this session (expired token, or the account lost staff access). */
export function onSessionEnded(handler: () => void) {
  sessionEnded = handler;
}

export class HttpError extends Error {
  constructor(public readonly error: ApiError) {
    super(error.detail || `Request failed (${error.status})`);
  }
}

async function toApiError(res: Response): Promise<ApiError> {
  try {
    const data = await res.json();
    return { status: res.status, code: data.code, detail: data.detail || data.title, errors: data.errors };
  } catch {
    return { status: res.status, detail: res.statusText };
  }
}

type Options = { method?: string; body?: unknown; auth?: boolean };

function endSessionIfRefused(res: Response) {
  // 401: token missing/expired. 403: signed in, but no longer allowed to use the support API.
  if (res.status === 401 || res.status === 403) {
    setToken(null);
    sessionEnded?.();
  }
}

export async function api<T>(path: string, opts: Options = {}): Promise<T> {
  const { method = 'GET', body, auth = true } = opts;
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth && authToken) headers['Authorization'] = `Bearer ${authToken}`;

  const res = await fetch(`${BASE}/api/v1${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (auth) endSessionIfRefused(res);
  if (!res.ok) throw new HttpError(await toApiError(res));
  return res.json() as Promise<T>;
}

/** Authenticated file download (a plain link cannot send the auth header). */
export async function downloadBlob(path: string): Promise<Blob> {
  const headers: Record<string, string> = {};
  if (authToken) headers['Authorization'] = `Bearer ${authToken}`;
  const res = await fetch(`${BASE}/api/v1${path}`, { headers });
  endSessionIfRefused(res);
  if (!res.ok) throw new HttpError(await toApiError(res));
  return res.blob();
}

/** Hands a blob to the browser as a file download. */
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
