import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { HANDOFFCHECK_PLAN_MESSAGE } from '../lib/format';
import { ActionMenu } from './ActionMenu';
import type { ActionMenuItem } from './ActionMenu';
import { ChangePasswordModal } from './ChangePasswordModal';
import { ThemeToggle } from './ThemeToggle';

export function Layout() {
  const { user, logout } = useAuth();
  // Locked only when the backend says so (the same test the HandoffCheck page uses), never because it said nothing.
  const handoffCheckLocked = user?.handoffCheck === false;
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const onAccountPages = pathname.startsWith('/account');
  const [changingPassword, setChangingPassword] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);   // the menu of a narrow screen (a wide one always shows it)

  useEffect(() => { setMenuOpen(false); }, [pathname]);   // arriving somewhere closes it
  useEffect(() => {
    if (!menuOpen) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setMenuOpen(false); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [menuOpen]);

  // One entry for everything about the customer's own account; each item is the page or dialog that already existed.
  // Wide screens show it as a dropdown (ActionMenu), narrow ones as a list inside the menu, both from this one list.
  const accountItems: ActionMenuItem[] = [
    { label: 'My Profile', onSelect: () => navigate('/account') },
    { label: 'Change Password', onSelect: () => setChangingPassword(true) },
    { label: 'Jobs', onSelect: () => navigate('/account/jobs') },
    { label: 'Jobs Monitoring History', onSelect: () => navigate('/account/jobs/history') },
  ];

  return (
    <div className="app-shell">
      <header className="topbar">
        <NavLink to="/dashboard" className="brand">Hand<span>Offly</span></NavLink>
        <button type="button" className="btn btn-sm nav-toggle" aria-expanded={menuOpen} aria-controls="site-menu"
                aria-label={menuOpen ? 'Close menu' : 'Open menu'} onClick={() => setMenuOpen(!menuOpen)}>
          <span aria-hidden="true">{menuOpen ? '✕' : '☰'}</span>
        </button>
        <div id="site-menu" className={`site-menu${menuOpen ? ' open' : ''}`}>
          <nav aria-label="Main" onClick={() => setMenuOpen(false)}>
            <NavLink to="/dashboard" end>Dashboard</NavLink>
            <NavLink to="/handoffs/new">New handoff</NavLink>
            {/* Shown to every plan; dimmed and locked when the plan does not include it (the page explains, the backend refuses). */}
            <NavLink to="/handoff-check" className={handoffCheckLocked ? 'nav-locked' : undefined}
                     title={handoffCheckLocked ? HANDOFFCHECK_PLAN_MESSAGE : undefined}>
              HandoffCheck{handoffCheckLocked && <span aria-hidden="true"> 🔒</span>}
            </NavLink>
            <NavLink to="/reports">Reports</NavLink>
          </nav>
          <div className="nav-actions">
            <div className="account-desktop">
              <ActionMenu label="Account" openOnHover triggerClassName={`nav-menu-trigger${onAccountPages ? ' active' : ''}`} items={accountItems} />
            </div>
            <div className="account-inline" role="group" aria-label="Account">
              <div className="account-inline-label">Account</div>
              {accountItems.map((item) => (
                <button key={item.label} type="button" className="account-inline-item"
                        onClick={() => { setMenuOpen(false); item.onSelect(); }}>{item.label}</button>
              ))}
            </div>
            {user && <button className="btn btn-sm" onClick={logout}>Sign out</button>}
          </div>
        </div>
        <ThemeToggle />
      </header>
      <main className="container">
        <Outlet />
      </main>
      {changingPassword && <ChangePasswordModal onClose={() => setChangingPassword(false)} />}
    </div>
  );
}
