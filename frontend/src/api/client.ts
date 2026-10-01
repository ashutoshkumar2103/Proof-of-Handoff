import type { ApiError } from './types';

const BASE = import.meta.env.VITE_API_BASE_URL ?? '';
const TOKEN_KEY = 'handoffly.token';

// Module-level token cache so non-React callers (interceptors) can read it.
let authToken: string | null = localStorage.getItem(TOKEN_KEY);

export function setToken(token: string | null) {
  authToken = token;
  if (token) localStorage.setItem(TOKEN_KEY, token);
  else localStorage.removeItem(TOKEN_KEY);
}

export function getToken(): string | null {
  return authToken;
}

export class HttpError extends Error {
  constructor(public readonly error: ApiError) {
    super(error.detail || `Request failed (${error.status})`);
  }
}

type Options = {
  method?: string;
  body?: unknown;
  auth?: boolean; // default true
};

async function toApiError(res: Response): Promise<ApiError> {
  try {
    const data = await res.json();
    return {
      status: res.status,
      code: data.code,
      detail: data.detail || data.title,
      errors: data.errors,
    };
  } catch {
    return { status: res.status, detail: res.statusText };
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

  if (res.status === 401 && auth) {
    // Token invalid/expired — clear it so the app redirects to login.
    setToken(null);
  }
  if (!res.ok) {
    throw new HttpError(await toApiError(res));
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

/** Multipart upload helper (used for attachments). */
export async function upload<T>(path: string, form: FormData): Promise<T> {
  const headers: Record<string, string> = {};
  if (authToken) headers['Authorization'] = `Bearer ${authToken}`;
  const res = await fetch(`${BASE}/api/v1${path}`, { method: 'POST', headers, body: form });
  if (res.status === 401) setToken(null);
  if (!res.ok) throw new HttpError(await toApiError(res));
  return res.json() as Promise<T>;
}

/** Authenticated download (a plain link can't send the auth header): fetched as a blob, plus the server's filename. */
export async function downloadFile(path: string): Promise<{ blob: Blob; filename: string | null }> {
  const headers: Record<string, string> = {};
  if (authToken) headers['Authorization'] = `Bearer ${authToken}`;
  const res = await fetch(`${BASE}/api/v1${path}`, { headers });
  if (!res.ok) throw new HttpError(await toApiError(res));
  const name = res.headers.get('Content-Disposition')?.match(/filename="?([^";]+)"?/i)?.[1];
  return { blob: await res.blob(), filename: name ?? null };
}

export async function downloadBlob(path: string): Promise<Blob> {
  return (await downloadFile(path)).blob;
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
