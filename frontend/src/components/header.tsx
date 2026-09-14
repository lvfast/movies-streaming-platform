import { LogOut, Menu, Search, UserRound, X } from 'lucide-react';
import { type FormEvent, useEffect, useState } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { useSession } from '../session/session-context';

export function Header() {
  const { status, user, openAuth, signOut } = useSession();
  const location = useLocation();
  const navigate = useNavigate();
  const [searchOpen, setSearchOpen] = useState(location.pathname === '/search');
  const [query, setQuery] = useState(() => new URLSearchParams(location.search).get('q') ?? '');
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    setMenuOpen(false);
  }, [location.pathname]);

  function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = query.trim();
    if (!trimmed) return;
    navigate(`/search?q=${encodeURIComponent(trimmed)}`);
  }

  return (
    <header className="site-header">
      <Link className="brand" to="/browse" aria-label="LVFAST Cinema home">
        <img className="brand__logo" src="/logo/logo.png" alt="" />
        <span className="brand__wordmark">LVFAST<small>CINEMA</small></span>
      </Link>
      <button
        aria-expanded={menuOpen}
        aria-label={menuOpen ? 'Close navigation' : 'Open navigation'}
        className="icon-button site-header__menu"
        onClick={() => setMenuOpen((open) => !open)}
        type="button"
      >
        {menuOpen ? <X aria-hidden="true" /> : <Menu aria-hidden="true" />}
      </button>
      <nav className={menuOpen ? 'site-nav site-nav--open' : 'site-nav'} aria-label="Primary navigation">
        <NavLink to="/browse">Home</NavLink>
        {status === 'authenticated' ? <NavLink to="/my-list">My list</NavLink> : null}
      </nav>
      <div className="site-header__actions">
        <form className={searchOpen ? 'header-search header-search--open' : 'header-search'} onSubmit={submitSearch} role="search">
          {searchOpen ? (
            <input
              autoFocus
              aria-label="Search movies"
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Titles, genres…"
              type="search"
              value={query}
            />
          ) : null}
          <button
            aria-label={searchOpen ? 'Submit search' : 'Search'}
            className="icon-button"
            onClick={() => setSearchOpen(true)}
            type={searchOpen ? 'submit' : 'button'}
          >
            <Search aria-hidden="true" />
          </button>
        </form>
        {status === 'authenticated' && user ? (
          <div className="account-menu">
            <UserRound aria-hidden="true" size={18} />
            <span>{user.username}</span>
            <button className="icon-button" type="button" onClick={() => void signOut()} aria-label="Sign out">
              <LogOut aria-hidden="true" size={18} />
            </button>
          </div>
        ) : status === 'guest' ? (
          <button className="button button--compact button--primary" type="button" onClick={() => openAuth('login')}>
            Sign in
          </button>
        ) : (
          <span className="header-session-placeholder" aria-label="Restoring session" />
        )}
      </div>
    </header>
  );
}
