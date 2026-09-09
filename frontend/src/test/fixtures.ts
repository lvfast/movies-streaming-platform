import type {
  AuthTokens,
  CatalogHome,
  MovieDetails,
  MoviePage,
  MovieSummary,
  Playback,
  StreamingApi,
  User,
} from '../api/streaming-api';
import { vi } from 'vitest';

export const featuredMovie: MovieSummary = {
  id: '00000000-0000-0000-0000-000000000001',
  slug: 'starlight-archive',
  title: 'Starlight Archive',
  releaseYear: 2026,
  runtimeSeconds: 5400,
  maturityRating: 'PG',
  posterUrl: '/poster.svg',
  backdropUrl: '/backdrop.svg',
  genres: ['Adventure', 'Science Fiction'],
};

export const playableMovie: MovieDetails = {
  ...featuredMovie,
  synopsis: 'An archivist follows a signal hidden in a collection of star charts.',
  playable: true,
};

export const user: User = {
  id: '10000000-0000-0000-0000-000000000001',
  username: 'viewer_one',
};

export const authTokens: AuthTokens = {
  accessToken: 'memory-only-token',
  tokenType: 'Bearer',
  expiresIn: 900,
  user,
};

export const catalog: CatalogHome = {
  rails: [{ key: 'featured', title: 'Featured tonight', items: [featuredMovie] }],
};

export const page: MoviePage = {
  items: [featuredMovie],
  page: 0,
  size: 20,
  total: 1,
};

export const playback: Playback = {
  movieId: featuredMovie.id,
  manifestUrl: '/media/fixtures/starlight-archive/index.m3u8',
  resumePositionSeconds: 37,
};

export function createApi(overrides: Partial<StreamingApi> = {}): StreamingApi {
  return {
    restoreSession: vi.fn().mockResolvedValue(null),
    login: vi.fn().mockResolvedValue(authTokens),
    register: vi.fn().mockResolvedValue(authTokens),
    logout: vi.fn().mockResolvedValue(undefined),
    catalogHome: vi.fn().mockResolvedValue(catalog),
    search: vi.fn().mockResolvedValue(page),
    movieBySlug: vi.fn().mockResolvedValue(playableMovie),
    watchlist: vi.fn().mockResolvedValue(page),
    addToWatchlist: vi.fn().mockResolvedValue(undefined),
    removeFromWatchlist: vi.fn().mockResolvedValue(undefined),
    playback: vi.fn().mockResolvedValue(playback),
    updateProgress: vi.fn().mockResolvedValue({
      movieId: featuredMovie.id,
      positionSeconds: 37,
      durationSeconds: 5400,
      clientUpdatedAt: '2026-09-07T00:00:00.000Z',
      completed: false,
    }),
    ...overrides,
  };
}
