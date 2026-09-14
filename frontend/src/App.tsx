import { lazy, Suspense } from 'react';
import { useLocation } from 'react-router-dom';
import { Route, Routes } from 'react-router-dom';
import { AuthDialog } from './components/auth-dialog';
import { LoadingState } from './components/feedback';
import { Header } from './components/header';
import { DetailsPage } from './pages/details-page';
import { HomePage } from './pages/home-page';
import { LandingPage } from './pages/landing-page';
import { SearchPage } from './pages/search-page';
import { WatchlistPage } from './pages/watchlist-page';
import { SessionProvider } from './session/session-context';

const PlayerPage = lazy(() => import('./pages/player-page').then((module) => ({
  default: module.PlayerPage,
})));

const AdminRoutes = lazy(() => import('./admin/admin-routes').then((module) => ({
  default: module.AdminRoutes,
})));

export function App() {
  return (
    <SessionProvider>
      <AppRoutes />
      <AuthDialog />
    </SessionProvider>
  );
}

function AppRoutes() {
  const location = useLocation();
  const playerRoute = location.pathname.startsWith('/watch/');
  const landingRoute = location.pathname === '/';
  const adminRoute = location.pathname.startsWith('/admin');

  return (
    <div className="app-shell">
      {!playerRoute && !landingRoute && !adminRoute ? <Header /> : null}
      <Routes>
        <Route path="/" element={<LandingPage />} />
        <Route path="/browse" element={<HomePage />} />
        <Route path="/search" element={<SearchPage />} />
        <Route path="/my-list" element={<WatchlistPage />} />
        <Route path="/title/:slug" element={<DetailsPage />} />
        <Route
          path="/watch/:movieId"
          element={(
            <Suspense fallback={<main className="player-page player-page--center"><LoadingState label="Preparing the player" /></main>}>
              <PlayerPage />
            </Suspense>
          )}
        />
        <Route
          path="/admin/*"
          element={(
            <Suspense fallback={<main className="admin-page admin-page--center"><LoadingState label="Loading the dashboard" /></main>}>
              <AdminRoutes />
            </Suspense>
          )}
        />
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </div>
  );
}

function NotFoundPage() {
  return (
    <main className="page page--center">
      <section className="empty-state">
        <p className="eyebrow">404</p>
        <h1>This scene is not in the catalog</h1>
        <a className="button button--primary" href="/">Back home</a>
      </section>
    </main>
  );
}
