import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ThemeToggle } from './ThemeToggle';

export function Layout() {
  const { user, logout } = useAuth();
  return (
    <div className="app-shell">
      <header className="topbar">
        <NavLink to="/dashboard" className="brand">Hand<span>Offly</span></NavLink>
        <nav>
          <NavLink to="/dashboard" end>Dashboard</NavLink>
          <NavLink to="/handoffs/new">New handoff</NavLink>
          <NavLink to="/handoff-check">HandoffCheck</NavLink>
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
