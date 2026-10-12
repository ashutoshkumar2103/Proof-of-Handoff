import { useEffect, useState } from 'react';

/** How long a message that reports what the user just did stays on screen. */
export const TRANSIENT_NOTICE_MS = 5000;

/**
 * State for a message that reports the result of something the user just did (done, refused, failed): it clears itself after
 * five seconds on screen. The countdown pauses while the app is in the background, so it is never used up unseen. Setting a new
 * value, even identical text, restarts it. A page's own state (a failed load) is not for this: it stays while it is true.
 */
export function useTransient<T>(): [T | null, (value: T | null) => void] {
  const [value, setValue] = useState<T | null>(null);
  useEffect(() => {
    if (value === null) return;
    let remainingMs = TRANSIENT_NOTICE_MS;
    let startedAt = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const start = () => {
      startedAt = Date.now();
      timer = setTimeout(() => setValue(null), remainingMs);
    };
    const pause = () => {
      if (timer === undefined) return;
      clearTimeout(timer);
      timer = undefined;
      remainingMs = Math.max(0, remainingMs - (Date.now() - startedAt));
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
