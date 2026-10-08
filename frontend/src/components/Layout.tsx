import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { HANDOFFCHECK_PLAN_MESSAGE } from '../lib/format';
import { ActionMenu } from './ActionMenu';
import type { ActionMenuItem } from './ActionMenu';
import { ChangePasswordModal } from './ChangePasswordModal';
import { ThemeToggle } from './ThemeToggle';

/**
 * One entry of the bar that opens a list of pages or actions. Wide screens show it as a dropdown (ActionMenu), narrow ones as a list inside the
 * menu that opens below the bar, both from the one list of items.
 */
function NavMenu({ label, items, active, onChoose }: { label: string; items: ActionMenuItem[]; active: boolean; onChoose: () => void }) {
  return (
    <>
      <div className="nav-menu-desktop">
        <ActionMenu label={label} openOnHover triggerClassName={`nav-menu-trigger${active ? ' active' : ''}`} items={items} />
      </div>
      <div className="nav-menu-inline" role="group" aria-label={label}>
        <div className="nav-menu-inline-label">{label}</div>
        {items.map((item) => (
          <button key={item.label} type="button" className={`nav-menu-inline-item${item.current ? ' current' : ''}`}
                  aria-current={item.current ? 'page' : undefined} onClick={() => { onChoose(); item.onSelect(); }}>{item.label}</button>
        ))}
      </div>
    </>
  );
}

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
  const accountItems: ActionMenuItem[] = [
    { label: 'My Profile', onSelect: () => navigate('/account'), current: pathname === '/account' },
    { label: 'Change Password', onSelect: () => setChangingPassword(true) },   // a dialog over the page you are on, not a page of its own
    { label: 'Jobs', onSelect: () => navigate('/account/jobs'), current: pathname === '/account/jobs' },
    { label: 'Jobs Monitoring History', onSelect: () => navigate('/account/jobs/history'), current: pathname === '/account/jobs/history' },
  ];
  // The two views of the report: the handoffs themselves, and their totals.
  const reportItems: ActionMenuItem[] = [
    { label: 'Quotation List', onSelect: () => navigate('/reports'), current: pathname === '/reports' },
    { label: 'Summary Report', onSelect: () => navigate('/reports/summary'), current: pathname === '/reports/summary' },
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
            <NavMenu label="Reports" items={reportItems} active={pathname.startsWith('/reports')} onChoose={() => setMenuOpen(false)} />
          </nav>
          <div className="nav-actions">
            <NavMenu label="Account" items={accountItems} active={onAccountPages} onChoose={() => setMenuOpen(false)} />
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
