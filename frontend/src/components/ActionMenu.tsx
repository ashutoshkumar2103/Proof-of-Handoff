import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent } from 'react';

export interface ActionMenuItem {
  label: string;
  onSelect: () => void;
  danger?: boolean;
  disabled?: boolean;
  current?: boolean;   // the page the item leads to is the one on screen
}

const SCREEN_MARGIN = 8;   // px kept free between an open list and the edge of the screen

/**
 * A small dropdown: "More" for the rarer or destructive actions of a page, so the common ones stay easy to see, or a menu of the top bar.
 * Choosing an item closes it first and then does exactly what the item's handler does; clicking elsewhere, tabbing out or pressing Escape
 * closes it too, and the arrow keys move between the items. It decides nothing: which items exist, and whether they are allowed, is the
 * caller's (and the backend's).
 *
 * `openOnHover` (for a menu in the top bar) also opens it while the pointer is over it, on devices that have a hover; on a touch
 * screen, or from the keyboard, it opens by a click or tap as usual. `triggerClassName` restyles the button (default: a normal button).
 *
 * Where the list opens is the same for every menu: under the button's left edge, or — if it would not fit there — under its right edge, and
 * never outside the screen (it is moved just far enough, again when the window is resized). See docs/UI_GUIDELINES.md.
 */
export function ActionMenu({ label = 'More', items, openOnHover = false, triggerClassName = 'btn' }:
  { label?: string; items: ActionMenuItem[]; openOnHover?: boolean; triggerClassName?: string }) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const [shift, setShift] = useState(0);   // how far the list is moved sideways from the button's left edge, so that it stays on the screen
  const focusFirst = useRef(false);   // opened from the keyboard: the first item takes the focus once it is on screen
  const hovers = () => openOnHover && window.matchMedia?.('(hover: hover)').matches;

  useEffect(() => {
    if (!open) return;
    const onPointer = (e: MouseEvent) => { if (!root.current?.contains(e.target as Node)) setOpen(false); };
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      setOpen(false);
      trigger.current?.focus();
    };
    document.addEventListener('mousedown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  useLayoutEffect(() => {
    if (!open) { setShift(0); return; }
    const place = () => {
      const list = panel.current;
      const host = root.current?.getBoundingClientRect();
      if (!list || !host) return;
      const screen = document.documentElement.clientWidth;
      const room = screen - SCREEN_MARGIN - list.offsetWidth;   // the furthest left edge the list may have
      let left = host.left;
      if (left > room) left = host.right - list.offsetWidth;    // does not fit under the left edge: line up with the right edge instead
      setShift(Math.max(SCREEN_MARGIN, Math.min(left, room)) - host.left);
    };
    place();
    window.addEventListener('resize', place);
    return () => window.removeEventListener('resize', place);
  }, [open]);

  useEffect(() => {
    if (open && focusFirst.current) enabledItems()[0]?.focus();
    focusFirst.current = false;
  }, [open]);

  const enabledItems = () => Array.from(root.current?.querySelectorAll<HTMLButtonElement>('.menu-item:not(:disabled)') ?? []);

  function onTriggerKey(e: ReactKeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      focusFirst.current = true;
      if (open) enabledItems()[0]?.focus(); else setOpen(true);
    }
  }

  function onPanelKey(e: ReactKeyboardEvent) {
    const list = enabledItems();
    const at = list.indexOf(document.activeElement as HTMLButtonElement);
    const to = e.key === 'ArrowDown' ? (at + 1) % list.length : e.key === 'ArrowUp' ? (at - 1 + list.length) % list.length
      : e.key === 'Home' ? 0 : e.key === 'End' ? list.length - 1 : -1;
    if (to < 0) return;
    e.preventDefault();
    list[to]?.focus();
  }

  if (items.length === 0) return null;
  return (
    <div className="menu" ref={root}
         onMouseEnter={() => { if (hovers()) setOpen(true); }} onMouseLeave={() => { if (hovers()) setOpen(false); }}
         onBlur={(e) => { if (open && e.relatedTarget && !root.current?.contains(e.relatedTarget as Node)) setOpen(false); }}>
      <button ref={trigger} type="button" className={triggerClassName} aria-haspopup="menu" aria-expanded={open}
              onKeyDown={onTriggerKey} onClick={() => setOpen(hovers() ? true : !open)}>
        {label} <span aria-hidden="true" className="menu-caret">▾</span>
      </button>
      {open && (
        <div className="menu-panel" role="menu" ref={panel} style={{ left: shift }} onKeyDown={onPanelKey}>
          {items.map((item) => (
            <button key={item.label} type="button" role="menuitem" disabled={item.disabled} aria-current={item.current ? 'page' : undefined}
                    className={`menu-item${item.danger ? ' danger' : ''}${item.current ? ' current' : ''}`}
                    onClick={() => { setOpen(false); item.onSelect(); }}>
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
