import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ThemeToggle } from './ThemeToggle';

const LIFECYCLE = ['Give', 'Acknowledge', 'Active', 'Return', 'Closed'];

/**
 * Split-screen authentication layout shared by the login and register pages: a branded,
 * project-themed panel on the left and the form on the right. Keeps both pages visually
 * consistent without duplicating the layout.
 */
export function AuthShell({ children }: { children: ReactNode }) {
  return (
    <div className="auth-shell">
      <aside className="auth-brand-panel">
        <div className="auth-brand-top">
          <Link to="/" className="brand brand-lg">Hand<span>Offly</span></Link>
        </div>
        <div className="auth-brand-body">
          <h2>Proof of every handoff, tracked until it's returned.</h2>
          <p>
            Record what you hand over, capture the recipient's acknowledgement, and track every
            return — partials, multiples and missing items — until everything is accounted for.
          </p>
          <div className="auth-lifecycle">
            {LIFECYCLE.map((s, i) => (
              <span key={s} className="auth-chip">
                {s}{i < LIFECYCLE.length - 1 && <span className="auth-chip-arrow">→</span>}
              </span>
            ))}
          </div>
        </div>
        <div className="auth-brand-foot muted small">
          Construction · Events · IT · Rentals · Documents · Keys
        </div>
      </aside>
      <div className="auth-form-area">
        <div className="auth-form-topbar"><ThemeToggle /></div>
        {children}
      </div>
    </div>
  );
}
