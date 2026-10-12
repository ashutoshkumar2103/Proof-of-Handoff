import { useEffect, useState } from 'react';
import { Link, NavLink, Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { Icon } from './Icon';
import { Spinner } from './ui';

/** Says so when the phone has no connection: the app needs the HandOffly server for everything it shows or records. */
export function OfflineBanner() {
  const [online, setOnline] = useState(() => navigator.onLine);
  useEffect(() => {
    const on = () => setOnline(true);
    const off = () => setOnline(false);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => { window.removeEventListener('online', on); window.removeEventListener('offline', off); };
  }, []);
  if (online) return null;
  return (
    <div className="offline-banner" role="alert">
      You’re offline. HandOffly needs a connection to show handoffs and to save anything — nothing is saved on this phone to send later.
    </div>
  );
}

/** Screens that say what an ended plan means for them in their own words, so the shell's general banner stays out of the way. */
const QUIET_ON = ['/new', '/reports'];

/** A new screen opens at its top, not wherever the last one was scrolled to. */
export function ScrollToTop() {
  const { pathname } = useLocation();
  useEffect(() => { window.scrollTo(0, 0); }, [pathname]);
  return null;
}

/** The signed-in screens: a compact top bar, the page, and a bottom bar the thumb reaches. */
export function AppShell() {
  const { user, loading, unreachable, retry } = useAuth();
  const location = useLocation();
  if (loading) return <div className="boot"><Spinner /></div>;
  if (!user && unreachable) {
    return (
      <div className="app">
        <header className="topbar"><span className="brand">Hand<span>Offly</span></span></header>
        <OfflineBanner />
        <main className="page stack center">
          <h1>Can’t reach HandOffly</h1>
          <p className="muted">You’re still signed in. Check your connection, then try again.</p>
          <button className="btn btn-primary btn-block" onClick={retry}>Try again</button>
        </main>
      </div>
    );
  }
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;

  const inactive = user.subscription.status === 'INACTIVE';
  return (
    <div className="app">
      <header className="topbar">
        <Link to="/" className="brand" aria-label="HandOffly home">Hand<span>Offly</span></Link>
        <Link to="/reports" className={`topbar-link${location.pathname.startsWith('/reports') ? ' active' : ''}`}
              aria-current={location.pathname.startsWith('/reports') ? 'page' : undefined}>
          <Icon name="chart" />Reports
        </Link>
      </header>
      <ScrollToTop />
      <OfflineBanner />
      <main className="page stack" id="main">
        {inactive && !QUIET_ON.includes(location.pathname) && (
          <div className="notice notice-warning" role="status">
            {user.subscription.plan
              ? 'Your plan has ended. You can still see your handoffs, record returns and download PDFs, but not start or send new ones.'
              : 'No plan is active on this account yet, so new handoffs can’t be started. Contact support to activate it.'}
          </div>
        )}
        <Outlet />
      </main>
      <nav className="bottomnav" aria-label="Main">
        <NavLink to="/" end className={({ isActive }) => `navitem${isActive ? ' active' : ''}`}>
          <Icon name="handoffs" />Handoffs
        </NavLink>
        <NavLink to="/new" className={({ isActive }) => `navitem navitem-new${isActive ? ' active' : ''}`}>
          <span className="navplus"><Icon name="plus" /></span>New
        </NavLink>
        <NavLink to="/account" className={({ isActive }) => `navitem${isActive ? ' active' : ''}`}>
          <Icon name="user-circle" />Account
        </NavLink>
      </nav>
    </div>
  );
}
