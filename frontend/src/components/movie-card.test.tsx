import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import '../styles.css';
import { featuredMovie, playableMovie } from '../test/fixtures';
import { MovieRail } from './movie-card';

describe('MovieRail', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('keeps snap positions aligned with the horizontal rail padding', () => {
    render(
      <MemoryRouter>
        <MovieRail title="New releases" movies={[featuredMovie]} />
      </MemoryRouter>,
    );

    const track = screen.getByRole('heading', { name: 'New releases' }).nextElementSibling;
    expect(track).not.toBeNull();

    const style = window.getComputedStyle(track as Element);
    expect(style.scrollPaddingInline).not.toBe('');
    expect(style.scrollPaddingInline).toBe(style.paddingInline);
  });

  it('renders the featured rail with portrait cards', () => {
    render(
      <MemoryRouter>
        <MovieRail title="Featured" movies={[featuredMovie]} variant="featured" />
      </MemoryRouter>,
    );

    const rail = screen.getByRole('region', { name: 'Featured' });
    expect(rail).toHaveClass('movie-rail--featured');
  });

  it('scrolls every rail horizontally when it is dragged', () => {
    render(
      <MemoryRouter>
        <MovieRail title="New releases" movies={[featuredMovie]} />
      </MemoryRouter>,
    );

    const track = screen.getByRole('region', { name: 'New releases' }).querySelector('.movie-rail__track') as HTMLElement;
    track.scrollLeft = 120;

    fireEvent.pointerDown(track, { button: 0, clientX: 200, pointerId: 1 });
    fireEvent.pointerMove(track, { clientX: 150, pointerId: 1 });
    fireEvent.pointerUp(track, { clientX: 150, pointerId: 1 });

    expect(track.scrollLeft).toBe(170);
  });

  it('does not capture a pointer until a press becomes a drag', () => {
    render(
      <MemoryRouter>
        <MovieRail title="Featured" movies={[featuredMovie]} variant="featured" />
      </MemoryRouter>,
    );

    const track = screen.getByRole('region', { name: 'Featured' }).querySelector('.movie-rail__track') as HTMLElement;
    const link = screen.getByRole('link', { name: `More information about ${featuredMovie.title}` });
    const capturePointer = vi.fn();
    track.setPointerCapture = capturePointer;

    fireEvent.pointerDown(link, { button: 0, clientX: 200, pointerId: 1 });

    expect(capturePointer).not.toHaveBeenCalled();
  });

  it('keeps moving with decaying momentum after a fast drag is released', () => {
    vi.useFakeTimers();
    render(
      <MemoryRouter>
        <MovieRail title="New releases" movies={[featuredMovie]} />
      </MemoryRouter>,
    );

    const track = screen.getByRole('region', { name: 'New releases' }).querySelector('.movie-rail__track') as HTMLElement;
    track.scrollLeft = 120;

    fireEvent.pointerDown(track, { button: 0, clientX: 200, pointerId: 1 });
    fireEvent.pointerMove(track, { clientX: 150, pointerId: 1 });
    fireEvent.pointerUp(track, { clientX: 150, pointerId: 1 });
    const releasedAt = track.scrollLeft;

    vi.advanceTimersByTime(32);

    expect(track.scrollLeft).toBeGreaterThan(releasedAt);
  });

  it('opens an enriched movie preview only after the hover delay', async () => {
    vi.useFakeTimers();
    const loadDetails = vi.fn().mockResolvedValue(playableMovie);
    render(
      <MemoryRouter>
        <MovieRail
          movies={[featuredMovie]}
          preview={{ loadDetails }}
          title="Featured"
          variant="featured"
        />
      </MemoryRouter>,
    );

    fireEvent.pointerEnter(screen.getByRole('article'));
    await act(async () => vi.advanceTimersByTimeAsync(349));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    await act(async () => vi.advanceTimersByTimeAsync(1));

    expect(screen.getByRole('dialog', { name: featuredMovie.title })).toBeVisible();
    expect(screen.getByText(playableMovie.synopsis)).toBeVisible();
    expect(loadDetails).toHaveBeenCalledOnce();
  });

  it('exposes play, watchlist, and details actions from the preview', async () => {
    vi.useFakeTimers();
    const onPlay = vi.fn();
    const onToggleWatchlist = vi.fn();
    render(
      <MemoryRouter>
        <MovieRail
          movies={[featuredMovie]}
          preview={{
            loadDetails: vi.fn().mockResolvedValue(playableMovie),
            onPlay,
            onToggleWatchlist,
            savedMovieIds: new Set(),
          }}
          title="Featured"
        />
      </MemoryRouter>,
    );

    fireEvent.pointerEnter(screen.getByRole('article'));
    await act(async () => vi.advanceTimersByTimeAsync(350));
    const dialog = screen.getByRole('dialog', { name: featuredMovie.title });

    expect(within(dialog).getByRole('link', { name: 'More information about Starlight Archive' })).toHaveAttribute(
      'href',
      '/title/starlight-archive',
    );
    fireEvent.click(within(dialog).getByRole('button', { name: 'Play Starlight Archive' }));
    expect(onPlay).toHaveBeenCalledWith(playableMovie);

    fireEvent.pointerEnter(screen.getByRole('article'));
    await act(async () => vi.advanceTimersByTimeAsync(350));
    fireEvent.click(within(screen.getByRole('dialog', { name: featuredMovie.title })).getByRole(
      'button',
      { name: 'Add Starlight Archive to my list' },
    ));
    expect(onToggleWatchlist).toHaveBeenCalledWith(featuredMovie);
  });

  it('keeps the preview inside the viewport near the bottom edge', async () => {
    vi.useFakeTimers();
    vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(900);
    render(
      <MemoryRouter>
        <MovieRail
          movies={[featuredMovie]}
          preview={{ loadDetails: vi.fn().mockResolvedValue(playableMovie) }}
          title="Featured"
        />
      </MemoryRouter>,
    );

    const card = screen.getByRole('article');
    vi.spyOn(card, 'getBoundingClientRect').mockReturnValue({
      bottom: 1000,
      height: 200,
      left: 500,
      right: 800,
      top: 800,
      width: 300,
      x: 500,
      y: 800,
      toJSON: () => undefined,
    });
    fireEvent.pointerEnter(card);
    await act(async () => vi.advanceTimersByTimeAsync(350));

    expect(screen.getByRole('dialog', { name: featuredMovie.title })).toHaveStyle({ top: '474px' });
  });
});
