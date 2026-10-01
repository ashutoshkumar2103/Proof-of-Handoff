import type { ReactNode } from 'react';
import { HttpError } from '../api/client';

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
