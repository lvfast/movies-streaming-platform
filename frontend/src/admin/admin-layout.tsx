import { ArrowLeft, LogOut, Menu, X } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { useSession } from '../session/session-context';

export function AdminLayout() {
  const { user, signOut } = useSession();
  const location = useLocation();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuOpenRef = useRef(false);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);

  const openMenu = useCallback(() => {
    menuOpenRef.current = true;
    setMenuOpen(true);
  }, []);

  const closeMenu = useCallback(() => {
    menuOpenRef.current = false;
    setMenuOpen(false);
    toggleRef.current?.focus();
  }, []);

  useEffect(() => {
    if (menuOpenRef.current) closeMenu();
  }, [location.pathname, closeMenu]);

  useEffect(() => {
    if (menuOpen) closeRef.current?.focus();
  }, [menuOpen]);

  useEffect(() => {
    if (!menuOpen) return;
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') closeMenu();
    }
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [menuOpen, closeMenu]);

  return (
    <div className="admin-shell">
      <header className="admin-topbar">
        <button
          ref={toggleRef}
          className="admin-menu-toggle"
          type="button"
          aria-label="Open navigation"
          aria-expanded={menuOpen}
          onClick={openMenu}
        >
          <Menu aria-hidden="true" size={20} />
        </button>
        <Link className="admin-brand" to="/admin/movies">
          <img className="admin-brand__logo" src="/logo/logo.png" alt="" />
          <span>LVFAST<strong> Admin</strong></span>
        </Link>
      </header>

      {menuOpen ? <div className="admin-overlay" aria-hidden="true" onClick={closeMenu} /> : null}

      <aside className={menuOpen ? 'admin-sidebar admin-sidebar--open' : 'admin-sidebar'}>
        <button ref={closeRef} className="admin-menu-close" type="button" aria-label="Close navigation" onClick={closeMenu}>
          <X aria-hidden="true" size={20} />
        </button>
        <Link className="admin-brand" to="/admin/movies" onClick={closeMenu}>
          <img className="admin-brand__logo" src="/logo/logo.png" alt="" />
          <span>LVFAST<strong> Admin</strong></span>
        </Link>
        <nav className="admin-nav" aria-label="Admin navigation">
          <NavLink to="/admin/movies" onClick={closeMenu}>Films</NavLink>
          <NavLink to="/admin/jobs" onClick={closeMenu}>Jobs</NavLink>
          <NavLink to="/admin/audit" onClick={closeMenu}>Audit</NavLink>
        </nav>
        <div className="admin-sidebar__footer">
          {user ? <span className="admin-account">{user.username}</span> : null}
          <Link className="admin-back" to="/browse" onClick={closeMenu}>
            <ArrowLeft aria-hidden="true" size={16} />
            View site
          </Link>
          <button className="admin-signout" type="button" onClick={() => void signOut()}>
            <LogOut aria-hidden="true" size={16} />
            Sign out
          </button>
        </div>
      </aside>

      <div className="admin-main">
        <Outlet />
      </div>
    </div>
  );
}
