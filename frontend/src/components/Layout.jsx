import { NavLink, Navigate, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { Loading } from './Feedback.jsx';

/** Top navigation bar; admin links only appear for the ADMIN role. */
export function NavBar() {
  const { user, isAdmin, signOut } = useAuth();
  const navigate = useNavigate();

  function logout() {
    signOut();
    navigate('/login');
  }

  return (
    <header className="navbar">
      <span className="brand">Wallet</span>
      <nav>
        <NavLink to="/" end>Dashboard</NavLink>
        <NavLink to="/transfer">Transfer</NavLink>
        <NavLink to="/history">History</NavLink>
        <NavLink to="/lookup">Wallet lookup</NavLink>
        <NavLink to="/profile">Profile</NavLink>
        {isAdmin && <NavLink to="/admin">Admin</NavLink>}
      </nav>
      <div className="nav-user">
        {user && <span>{user.username}</span>}
        <button type="button" className="secondary" onClick={logout}>
          Log out
        </button>
      </div>
    </header>
  );
}

/** Route guard: redirects to /login when not authenticated, otherwise renders the page inside the layout. */
export function ProtectedLayout() {
  const { authenticated, ready } = useAuth();
  if (!authenticated) return <Navigate to="/login" replace />;
  if (!ready) return <Loading text="Loading your account..." />;
  return (
    <>
      <NavBar />
      <main className="container">
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
