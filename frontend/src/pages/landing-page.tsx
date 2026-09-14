import { ArrowRight } from 'lucide-react';
import { Link } from 'react-router-dom';

export function LandingPage() {
  return (
    <main className="landing-page">
      <div className="landing-page__wall" aria-hidden="true" />
      <div className="landing-page__veil" aria-hidden="true" />

      <section className="landing-page__panel" aria-labelledby="landing-title">
        <div className="landing-page__brand" aria-label="LVFAST Cinema">
          <span className="landing-page__brand-mark"><img src="/logo/logo.png" alt="" /></span>
          <span>LVFAST<small>CINEMA</small></span>
        </div>
        <p className="landing-page__eyebrow">Your screen. Your escape.</p>
        <h1 id="landing-title">Stories worth staying for.</h1>
        <p className="landing-page__intro">
          Discover a hand-picked collection of films, settle in, and let the next great story find you.
        </p>
        <Link className="landing-page__cta" to="/browse">
          Browse movies
          <ArrowRight aria-hidden="true" />
        </Link>
        <div className="landing-page__features" aria-label="Cinema highlights">
          <span>Curated nightly</span>
          <span>HD streaming</span>
          <span>Resume anywhere</span>
        </div>
      </section>
    </main>
  );
}
