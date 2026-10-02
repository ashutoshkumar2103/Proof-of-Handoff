import { useEffect, useRef, useState } from 'react';

export interface ActionMenuItem {
  label: string;
  onSelect: () => void;
  danger?: boolean;
  disabled?: boolean;
}

/**
 * A small "More" dropdown for the rarer or destructive actions of a page, so the common ones stay easy to see. Choosing an
 * item closes it first and then does exactly what the item's handler does; clicking elsewhere or pressing Escape closes it too.
 * It decides nothing: which items exist, and whether they are allowed, is the caller's (and the backend's).
 *
 * `openOnHover` (for a menu in the top bar) also opens it while the pointer is over it, on devices that have a hover; on a touch
 * screen, or from the keyboard, it opens by a click or tap as usual. `triggerClassName` restyles the button (default: a normal button).
 */
export function ActionMenu({ label = 'More', items, openOnHover = false, triggerClassName = 'btn' }:
  { label?: string; items: ActionMenuItem[]; openOnHover?: boolean; triggerClassName?: string }) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const hovers = () => openOnHover && window.matchMedia?.('(hover: hover)').matches;

  useEffect(() => {
    if (!open) return;
    const onPointer = (e: MouseEvent) => { if (!root.current?.contains(e.target as Node)) setOpen(false); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  if (items.length === 0) return null;
  return (
    <div className={`menu${openOnHover ? ' menu-hover' : ''}`} ref={root}
         onMouseEnter={() => { if (hovers()) setOpen(true); }} onMouseLeave={() => { if (hovers()) setOpen(false); }}>
      <button type="button" className={triggerClassName} aria-haspopup="menu" aria-expanded={open}
              onClick={() => setOpen(hovers() ? true : !open)}>
        {label} <span aria-hidden="true">▾</span>
      </button>
      {open && (
        <div className="menu-panel" role="menu">
          {items.map((item) => (
            <button key={item.label} type="button" role="menuitem" disabled={item.disabled}
                    className={`menu-item${item.danger ? ' danger' : ''}`}
                    onClick={() => { setOpen(false); item.onSelect(); }}>
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
