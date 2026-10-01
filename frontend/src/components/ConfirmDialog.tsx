import { createContext, useCallback, useContext, useRef, useState } from 'react';
import type { ReactNode } from 'react';

export interface ConfirmOptions {
  title: string;
  message?: ReactNode;
  confirmText?: string;
  cancelText?: string;
  danger?: boolean;
  /** When present, the dialog shows a text input (replaces window.prompt). */
  input?: { label?: string; placeholder?: string; required?: boolean; defaultValue?: string };
  /**
   * When present, the confirm button is replaced by one button per choice. Picking one
   * resolves `{ confirmed: true, value: <choice value> }`; Cancel/Escape/backdrop resolve
   * `confirmed: false`.
   */
  choices?: { value: string; label: string; primary?: boolean }[];
}

export interface ConfirmResult {
  confirmed: boolean;
  value: string;
}

type ConfirmFn = (opts: ConfirmOptions) => Promise<ConfirmResult>;

const ConfirmContext = createContext<ConfirmFn | null>(null);

interface Pending { opts: ConfirmOptions; resolve: (r: ConfirmResult) => void }

/**
 * App-wide confirm/prompt dialog rendered as a modal (replaces window.confirm / prompt /
 * alert). One provider, reused everywhere via {@link useConfirm}.
 */
export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null);
  const [value, setValue] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  const confirm = useCallback<ConfirmFn>((opts) => {
    setValue(opts.input?.defaultValue ?? '');
    return new Promise<ConfirmResult>((resolve) => {
      setPending({ opts, resolve });
      // Focus the input/confirm button after render.
      setTimeout(() => inputRef.current?.focus(), 30);
    });
  }, []);

  function settle(confirmed: boolean, choice?: string) {
    if (!pending) return;
    pending.resolve({ confirmed, value: choice ?? value.trim() });
    setPending(null);
  }

  const opts = pending?.opts;
  const inputRequired = !!opts?.input?.required;
  const confirmDisabled = inputRequired && value.trim() === '';

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {opts && (
        <div className="modal-overlay" onMouseDown={() => settle(false)}>
          <div className="modal" role="dialog" aria-modal="true" onMouseDown={(e) => e.stopPropagation()}
               onKeyDown={(e) => {
                 if (e.key === 'Escape') settle(false);
                 if (e.key === 'Enter' && !opts.input && !opts.choices && !confirmDisabled) settle(true);
               }}>
            <h3 className="modal-title">{opts.title}</h3>
            {opts.message && <div className="modal-body">{opts.message}</div>}
            {opts.input && (
              <div className="field" style={{ marginTop: '0.5rem' }}>
                {opts.input.label && <label>{opts.input.label}</label>}
                <input ref={inputRef} value={value} placeholder={opts.input.placeholder}
                       onChange={(e) => setValue(e.target.value)}
                       onKeyDown={(e) => { if (e.key === 'Enter' && !confirmDisabled) settle(true); }} />
              </div>
            )}
            <div className="modal-actions">
              <button type="button" className="btn btn-ghost" onClick={() => settle(false)}>
                {opts.cancelText ?? 'Cancel'}
              </button>
              {opts.choices
                ? opts.choices.map((c) => (
                    <button key={c.value} type="button" className={`btn ${c.primary ? 'btn-primary' : ''}`}
                            onClick={() => settle(true, c.value)}>{c.label}</button>
                  ))
                : (
                  <button type="button"
                          className={`btn ${opts.danger ? 'btn-danger' : 'btn-primary'}`}
                          disabled={confirmDisabled} onClick={() => settle(true)}>
                    {opts.confirmText ?? 'Confirm'}
                  </button>
                )}
            </div>
          </div>
        </div>
      )}
    </ConfirmContext.Provider>
  );
}

/** Returns a `confirm(options)` that resolves when the user acts on the modal. */
// eslint-disable-next-line react-refresh/only-export-components
export function useConfirm(): ConfirmFn {
  const ctx = useContext(ConfirmContext);
  if (!ctx) throw new Error('useConfirm must be used within ConfirmProvider');
  return ctx;
}
