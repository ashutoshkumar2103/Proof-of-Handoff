import { useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { HANDOFFCHECK_PLAN_MESSAGE } from '../lib/format';
import { ActionMenu } from './ActionMenu';
import { ChangePasswordModal } from './ChangePasswordModal';
import { ThemeToggle } from './ThemeToggle';

export function Layout() {
  const { user, logout } = useAuth();
  // Locked only when the backend says so (the same test the HandoffCheck page uses), never because it said nothing.
  const handoffCheckLocked = user?.handoffCheck === false;
  const navigate = useNavigate();
  const onAccountPages = useLocation().pathname.startsWith('/account');
  const [changingPassword, setChangingPassword] = useState(false);
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
          <NavLink to="/reports">Reports</NavLink>
          {/* One entry for everything about the customer's own account; each item is the page or dialog that already existed. */}
          <ActionMenu label="Account" openOnHover triggerClassName={`nav-menu-trigger${onAccountPages ? ' active' : ''}`} items={[
            { label: 'My Profile', onSelect: () => navigate('/account') },
            { label: 'Change Password', onSelect: () => setChangingPassword(true) },
            { label: 'Jobs', onSelect: () => navigate('/account/jobs') },
            { label: 'Jobs Monitoring History', onSelect: () => navigate('/account/jobs/history') },
          ]} />
          <ThemeToggle />
          {user && <button className="btn btn-sm" onClick={logout}>Sign out</button>}
        </nav>
      </header>
      <main className="container">
        <Outlet />
      </main>
      {changingPassword && <ChangePasswordModal onClose={() => setChangingPassword(false)} />}
    </div>
  );
}
