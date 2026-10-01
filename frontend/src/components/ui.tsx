import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { HttpError } from '../api/client';

/** How long a temporary result/info message stays on screen. */
export const TRANSIENT_NOTICE_MS = 5000;

/**
 * State for a temporary message: it clears itself after {@link TRANSIENT_NOTICE_MS} of being on
 * screen. The countdown pauses while the browser tab is in the background (e.g. while you check
 * your inbox), so the message is never used up unseen. Setting a new value (even identical text)
 * restarts it. Errors are not meant for this — they stay.
 */
export function useTransient<T>(): [T | null, (value: T | null) => void] {
  const [value, setValue] = useState<T | null>(null);
  useEffect(() => {
    if (value === null) return;
    let remaining = TRANSIENT_NOTICE_MS;
    let startedAt = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const start = () => {
      startedAt = Date.now();
      timer = setTimeout(() => setValue(null), remaining);
    };
    const pause = () => {
      if (timer === undefined) return;
      clearTimeout(timer);
      timer = undefined;
      remaining = Math.max(0, remaining - (Date.now() - startedAt));
    };
    const onVisibility = () => (document.hidden ? pause() : timer === undefined && start());
    if (!document.hidden) start();
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [value]);
  return [value, setValue];
}

export function Spinner({ label }: { label?: string }) {
  return <p className="muted center mt-3">{label ?? 'Loading…'}</p>;
}

export function ErrorNotice({ error }: { error: unknown }) {
  const message = error instanceof HttpError
    ? error.error.detail ?? 'Something went wrong.'
    : error instanceof Error ? error.message : 'Something went wrong.';
  return <div className="notice notice-error">{message}</div>;
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="card center">
      <h3>{title}</h3>
      {children && <p className="muted">{children}</p>}
    </div>
  );
}

/** Extracts a user-friendly message from any thrown value. */
export function errorMessage(error: unknown): string {
  if (error instanceof HttpError) {
    if (error.error.errors?.length) {
      return error.error.errors.map((e) => `${e.field}: ${e.message}`).join('; ');
    }
    return error.error.detail ?? 'Request failed.';
  }
  return error instanceof Error ? error.message : 'Request failed.';
}
