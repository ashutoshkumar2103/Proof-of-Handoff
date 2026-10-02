import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { HANDOFFCHECK_PLAN_MESSAGE } from '../lib/format';
import { ThemeToggle } from './ThemeToggle';

export function Layout() {
  const { user, logout } = useAuth();
  // Locked only when the backend says so (the same test the HandoffCheck page uses), never because it said nothing.
  const handoffCheckLocked = user?.handoffCheck === false;
  return (
    <div className="app-shell">
      <header className="topbar">
        <NavLink to="/dashboard" className="brand">Hand<span>Offly</span></NavLink>
        <nav>
          <NavLink to="/dashboard" end>Dashboard</NavLink>
          <NavLink to="/handoffs/new">New handoff</NavLink>
          {/* Shown to every plan; dimmed and locked when the plan does not include it (the page explains, the backend refuses). */}
          <NavLink to="/handoff-check" className={handoffCheckLocked ? 'nav-locked' : undefined}
                   title={handoffCheckLocked ? HANDOFFCHECK_PLAN_MESSAGE : undefined}>
            HandoffCheck{handoffCheckLocked && <span aria-hidden="true"> 🔒</span>}
          </NavLink>
          <NavLink to="/account">Account</NavLink>
          <ThemeToggle />
          {user && (
            <>
              <span className="muted small" title={user.email}>{user.displayName}</span>
              <button className="btn btn-sm" onClick={logout}>Sign out</button>
            </>
          )}
        </nav>
      </header>
      <main className="container">
        <Outlet />
      </main>
    </div>
  );
}
