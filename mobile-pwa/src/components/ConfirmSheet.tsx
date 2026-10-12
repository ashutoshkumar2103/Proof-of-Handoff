import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';

interface ConfirmInput {
  label: string;
  placeholder?: string;
  required?: boolean;
  defaultValue?: string;
}

export interface ConfirmOptions {
  title: string;
  message?: ReactNode;
  confirmText?: string;
  cancelText?: string;
  /** The confirming button says "yes" to something destructive: it is red. */
  danger?: boolean;
  /** Asks for some text as well (a reason, a typed name). */
  input?: ConfirmInput;
}

export interface ConfirmResult {
  confirmed: boolean;
  value: string;
}

type Confirm = (options: ConfirmOptions) => Promise<ConfirmResult>;

const ConfirmContext = createContext<Confirm | undefined>(undefined);

/**
 * One confirmation sheet for the whole app, asked for with `await confirm({...})`. It rises from the bottom, where a thumb reaches
 * it, and is closed only by its own buttons or Escape — never by a tap outside, so a stray tap cannot throw away what was typed.
 */
export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<{ options: ConfirmOptions; resolve: (r: ConfirmResult) => void } | null>(null);

  const confirm = useCallback<Confirm>((options) => new Promise((resolve) => setPending({ options, resolve })), []);

  const settle = useCallback((result: ConfirmResult) => {
    setPending((p) => { p?.resolve(result); return null; });
  }, []);

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {pending && <Sheet options={pending.options} onSettle={settle} />}
    </ConfirmContext.Provider>
  );
}

function Sheet({ options, onSettle }: { options: ConfirmOptions; onSettle: (r: ConfirmResult) => void }) {
  const { title, message, confirmText = 'Confirm', cancelText = 'Cancel', danger, input } = options;
  const [value, setValue] = useState(input?.defaultValue ?? '');
  const [touched, setTouched] = useState(false);
  const sheetRef = useRef<HTMLFormElement>(null);
  const opener = useRef<Element | null>(document.activeElement);
  const missing = !!input?.required && value.trim() === '';

  const cancel = useCallback(() => onSettle({ confirmed: false, value: '' }), [onSettle]);

  useEffect(() => {
    const target = opener.current;
    const first = sheetRef.current?.querySelector<HTMLElement>('input, textarea, button[type="submit"]');
    first?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') cancel();
      if (e.key !== 'Tab' || !sheetRef.current) return;
      // Keep the focus inside the sheet while it is open.
      const focusable = [...sheetRef.current.querySelectorAll<HTMLElement>('input, textarea, button:not([disabled])')];
      if (focusable.length === 0) return;
      const firstEl = focusable[0];
      const lastEl = focusable[focusable.length - 1];
      const outside = !sheetRef.current.contains(document.activeElement);   // e.g. after a tap on the dimmed page behind
      if (outside) { e.preventDefault(); firstEl.focus(); }
      else if (e.shiftKey && document.activeElement === firstEl) { e.preventDefault(); lastEl.focus(); }
      else if (!e.shiftKey && document.activeElement === lastEl) { e.preventDefault(); firstEl.focus(); }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      if (target instanceof HTMLElement) target.focus();
    };
  }, [cancel]);

  function submit(e: React.FormEvent) {
    e.preventDefault();
    if (missing) { setTouched(true); return; }
    onSettle({ confirmed: true, value: value.trim() });
  }

  return (
    <div className="sheet-overlay">
      <form ref={sheetRef} className="sheet" role="dialog" aria-modal="true" aria-labelledby="sheet-title" onSubmit={submit} noValidate>
        <h2 className="sheet-title" id="sheet-title">{title}</h2>
        {message && <div className="muted sheet-message">{message}</div>}
        {input && (
          <div className="field">
            <label htmlFor="sheet-input">{input.label}</label>
            <input id="sheet-input" value={value} placeholder={input.placeholder} aria-invalid={touched && missing}
                   onChange={(e) => setValue(e.target.value)} />
            {touched && missing && <div className="form-error">This is required.</div>}
          </div>
        )}
        <div className="sheet-actions">
          <button type="button" className="btn btn-negative" onClick={cancel}>{cancelText}</button>
          <button type="submit" className={`btn ${danger ? 'btn-danger' : 'btn-primary'}`}>{confirmText}</button>
        </div>
      </form>
    </div>
  );
}

// eslint-disable-next-line react-refresh/only-export-components
export function useConfirm(): Confirm {
  const ctx = useContext(ConfirmContext);
  if (!ctx) throw new Error('useConfirm must be used within ConfirmProvider');
  return ctx;
}
