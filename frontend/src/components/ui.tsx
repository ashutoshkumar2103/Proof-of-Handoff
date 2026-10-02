import { useCallback, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { HttpError } from '../api/client';
import { NO_ACTIVE_SUBSCRIPTION_MESSAGE, SUBSCRIPTION_ENDED_MESSAGE } from '../lib/format';
import { useConfirm } from './ConfirmDialog';

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

export function EmptyState({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="card center">
      <h3>{title}</h3>
      {children && <p className="muted">{children}</p>}
      {action && <div className="mt-2">{action}</div>}
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

/** Content that cannot be used while `blocked`: every control inside is disabled, and whatever was typed is kept. */
export function Gated({ blocked, children }: { blocked: boolean; children: ReactNode }) {
  return <fieldset disabled={blocked} style={{ border: 0, padding: 0, margin: 0, minWidth: 0 }}>{children}</fieldset>;
}

/**
 * The one way the app tells a customer their subscription has ended (so they cannot start a handoff, send a draft or use
 * HandoffCheck):
 * the message, a single OK, then the Dashboard. Pass a signal to take the dialog down again from outside — e.g. when
 * the subscription turns out to be active after all — in which case nobody is sent anywhere.
 */
export function useSubscriptionEndedModal(): (signal?: AbortSignal) => Promise<void> {
  const confirm = useConfirm();
  const navigate = useNavigate();
  return useCallback((signal?: AbortSignal) => confirm({
    title: SUBSCRIPTION_ENDED_MESSAGE,
    confirmText: 'OK',
    hideCancel: true,
    signal,
  }).then(() => { if (!signal?.aborted) navigate('/dashboard'); }), [confirm, navigate]);
}

/**
 * What the app tells an account that has never had a plan (it signed up without paying, so nothing is active): the message
 * and the two ways to ask support to activate it — the existing Contact Support page and ticket form. Closing it only
 * closes it, unless {@code backToDashboard}: a page that cannot be used without a plan sends the customer back to the
 * Dashboard, as the ended-subscription dialog does — telling it the dialog was just seen, so it is not shown a second time.
 * Pass a signal to take it down again from outside, e.g. when a plan turns up.
 */
export function useNoActivePlanModal(): (signal?: AbortSignal, backToDashboard?: boolean) => Promise<void> {
  const confirm = useConfirm();
  const navigate = useNavigate();
  return useCallback((signal?: AbortSignal, backToDashboard = false) => confirm({
    title: NO_ACTIVE_SUBSCRIPTION_MESSAGE,
    cancelText: 'Close',
    choices: [
      { value: '/support', label: 'Contact Support', primary: true },
      { value: '/support/new', label: 'Create Ticket' },
    ],
    signal,
  }).then(({ confirmed, value }) => {
    if (signal?.aborted) return;
    if (confirmed) navigate(value);
    else if (backToDashboard) navigate('/dashboard', { state: { noPlanDialogSeen: true } });
  }), [confirm, navigate]);
}
