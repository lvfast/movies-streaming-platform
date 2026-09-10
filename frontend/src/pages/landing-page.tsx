import { ArrowRight, Play } from 'lucide-react';
import { Link } from 'react-router-dom';

const posterWall = [
  ['Starlight Archive', 'violet'],
  ['Paper Moons', 'amber'],
  ['Quiet Current', 'ocean'],
  ['Lanterns at Noon', 'sunset'],
  ['The Last Mapmaker', 'forest'],
  ['Garden of Gears', 'copper'],
  ['Northbound Tea', 'rose'],
  ['Clockwork Kite', 'sky'],
  ['Blue Hour Bakery', 'indigo'],
  ['Signal on the Hill', 'crimson'],
  ['Small Clouds', 'silver'],
  ['Winter Orchard', 'plum'],
] as const;

export function LandingPage() {
  return (
    <main className="landing-page">
      <div className="landing-page__wall" aria-hidden="true">
        {posterWall.map(([title, tone]) => (
          <span className={`landing-page__poster landing-page__poster--${tone}`} key={title}>
            <small>LVFAST ORIGINAL</small>
            <strong>{title}</strong>
          </span>
        ))}
      </div>
      <div className="landing-page__veil" aria-hidden="true" />

      <section className="landing-page__panel" aria-labelledby="landing-title">
        <div className="landing-page__brand" aria-label="LVFAST Cinema">
          <span className="landing-page__brand-mark"><Play aria-hidden="true" fill="currentColor" /></span>
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
