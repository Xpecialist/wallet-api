import { useEffect, useRef } from 'react';
import { NavLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { Loading } from './Feedback.jsx';

/** Small geometric logo mark (decorative). */
export function BrandMark() {
  return (
    <svg className="brand-mark" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <rect x="1" y="1" width="22" height="22" rx="6" fill="#2dd4bf" />
      <path d="M6 9.5h12M6 14.5h12" stroke="#04201c" strokeWidth="2.2" strokeLinecap="round" />
      <circle cx="16.5" cy="14.5" r="2" fill="#04201c" />
    </svg>
  );
}

/** Top navigation bar; admin links only appear for the ADMIN role. */
export function NavBar() {
  const { user, isAdmin, signOut } = useAuth();
  const navigate = useNavigate();
  const navRef = useRef(null);
  const { pathname } = useLocation();

  // On phones the links form one scrollable row: keep the active link in view.
  useEffect(() => {
    const nav = navRef.current;
    const active = nav && nav.querySelector('a.active');
    if (nav && active && nav.scrollWidth > nav.clientWidth) {
      nav.scrollLeft = active.offsetLeft - (nav.clientWidth - active.offsetWidth) / 2;
    }
  }, [pathname]);

  function logout() {
    signOut();
    navigate('/login');
  }

  return (
    <header className="navbar">
      <span className="brand">
        <BrandMark />
        Wallet
      </span>
      <nav aria-label="Main" ref={navRef}>
        <NavLink to="/" end>Dashboard</NavLink>
        <NavLink to="/transfer">Transfer</NavLink>
        <NavLink to="/deposits">Deposits</NavLink>
        <NavLink to="/history">History</NavLink>
        <NavLink to="/lookup">Wallet lookup</NavLink>
        <NavLink to="/profile">Profile</NavLink>
        {isAdmin && <NavLink to="/admin" className="nav-admin">Admin</NavLink>}
      </nav>
      <div className="nav-user">
        {user && <span className="nav-username">{user.username}</span>}
        <button type="button" className="ghost" onClick={logout}>
          Log out
        </button>
      </div>
    </header>
  );
}

/** Route guard: redirects to /login when not authenticated, otherwise renders the page inside the layout. */
export function ProtectedLayout() {
  const { authenticated, ready } = useAuth();
  const isAdminArea = useLocation().pathname.startsWith('/admin'); // admin tables get a wider column
  if (!authenticated) return <Navigate to="/login" replace />;
  if (!ready) return <Loading text="Loading your account..." page />;
  return (
    <>
      <NavBar />
      <main className={isAdminArea ? 'container container-wide' : 'container'}>
        <Outlet />
      </main>
    </>
  );
}

/** Route guard for admin pages. */
export function AdminOnly() {
  const { isAdmin } = useAuth();
  return isAdmin ? <Outlet /> : <Navigate to="/" replace />;
}
