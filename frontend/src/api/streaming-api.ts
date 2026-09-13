import {
  addToWatchlist as generatedAddToWatchlist,
  catalogHome as generatedCatalogHome,
  login as generatedLogin,
  logout as generatedLogout,
  movieBySlug as generatedMovieBySlug,
  playback as generatedPlayback,
  refresh as generatedRefresh,
  register as generatedRegister,
  removeFromWatchlist as generatedRemoveFromWatchlist,
  searchMovies as generatedSearchMovies,
  updateProgress as generatedUpdateProgress,
  watchlist as generatedWatchlist,
} from './generated';
import { client } from './generated/client.gen';
import type {
  AuthTokens,
  CatalogRail,
  Credentials,
  ManagedPlayback,
  MovieDetails,
  MoviePage,
  MovieSummary,
  Playback,
  Problem,
  Progress,
  ProgressUpdate,
  User,
} from './generated/types.gen';
import { accessTokenStore } from './token-store';

export type {
  AuthTokens,
  Credentials,
  ManagedPlayback,
  MovieDetails,
  MoviePage,
  MovieSummary,
  Playback,
  Problem,
  Progress,
  ProgressUpdate,
  User,
};

export type CatalogHome = { rails: Array<CatalogRail> };

/**
 * A playback response is either a managed version-pinned grant (with a media session
 * and short-lived token) or the legacy manifest/resume payload for local fixtures.
 */
export type PlaybackGrant = ManagedPlayback | Playback;

export type MediaTokenResponse = {
  mediaToken: string;
  mediaTokenExpiresAt: string;
};

/** Progress payload: a managed session sends its session and pinned media version. */
export type ProgressPayload = ProgressUpdate & {
  sessionId?: string;
  mediaVersionId?: string;
};

export class ProblemError extends Error {
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail);
    this.name = 'ProblemError';
    this.problem = problem;
  }
}

export interface StreamingApi {
  restoreSession(): Promise<AuthTokens | null>;
  login(credentials: Credentials): Promise<AuthTokens>;
  register(credentials: Credentials): Promise<AuthTokens>;
  logout(): Promise<void>;
  catalogHome(): Promise<CatalogHome>;
  search(query: string, page?: number, size?: number): Promise<MoviePage>;
  movieBySlug(slug: string): Promise<MovieDetails>;
  watchlist(page?: number, size?: number): Promise<MoviePage>;
  addToWatchlist(movieId: string): Promise<void>;
  removeFromWatchlist(movieId: string): Promise<void>;
  playback(movieId: string): Promise<PlaybackGrant>;
  updateProgress(movieId: string, progress: ProgressPayload): Promise<Progress>;
  mediaToken(sessionId: string): Promise<MediaTokenResponse>;
}

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api/v1';

client.setConfig({
  auth: () => accessTokenStore.get() ?? undefined,
  baseUrl: apiBaseUrl,
  credentials: 'include',
  responseStyle: 'fields',
  throwOnError: true,
});

function isProblem(value: unknown): value is Problem {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<Problem>;
  return (
    typeof candidate.status === 'number' &&
    typeof candidate.detail === 'string' &&
    typeof candidate.code === 'string' &&
    typeof candidate.requestId === 'string'
  );
}

function asProblemError(error: unknown): ProblemError {
  if (error instanceof ProblemError) return error;
  if (isProblem(error)) return new ProblemError(error);

  return new ProblemError({
    type: 'about:blank',
    title: 'Service unavailable',
    status: 0,
    detail: 'We could not reach the streaming service. Please try again.',
    requestId: 'unavailable',
    code: 'NETWORK_ERROR',
  });
}

async function call<T>(request: () => Promise<T>): Promise<T> {
  try {
    return await request();
  } catch (error) {
    throw asProblemError(error);
  }
}

function remember(tokens: AuthTokens): AuthTokens {
  accessTokenStore.set(tokens.accessToken);
  return tokens;
}

type AuthUserListener = (user: User) => void;
const authUserListeners = new Set<AuthUserListener>();

/** Subscribe to the user carried by a silent access-token refresh. */
export function subscribeAuthUser(listener: AuthUserListener): () => void {
  authUserListeners.add(listener);
  return () => {
    authUserListeners.delete(listener);
  };
}

export function notifyAuthUser(user: User): void {
  authUserListeners.forEach((listener) => listener(user));
}

async function refreshSession(): Promise<AuthTokens> {
  const tokens = await call(async () => (await generatedRefresh({ throwOnError: true })).data);
  remember(tokens);
  notifyAuthUser(tokens.user);
  return tokens;
}

export async function withAuthRetry<T>(request: () => Promise<T>): Promise<T> {
  try {
    return await call(request);
  } catch (error) {
    if (!(error instanceof ProblemError) || error.problem.status !== 401) throw error;
    try {
      await refreshSession();
    } catch (refreshError) {
      accessTokenStore.clear();
      throw refreshError;
    }
    return call(request);
  }
}

async function problemFromResponse(response: Response): Promise<ProblemError> {
  let problem: unknown = null;
  try {
    problem = await response.json();
  } catch {
    problem = null;
  }
  if (isProblem(problem)) return new ProblemError(problem);

  return new ProblemError({
    type: 'about:blank',
    title: response.statusText || 'Playback session unavailable',
    status: response.status,
    detail: 'We could not refresh the playback session. Please try again.',
    requestId: 'unavailable',
    code: 'MEDIA_TOKEN_ERROR',
  });
}

/**
 * The generated client does not describe the media token operation yet, so this
 * call mirrors the generated adapter: in-memory bearer token, cookie credentials
 * and RFC 9457 problem handling.
 */
async function requestMediaToken(sessionId: string): Promise<MediaTokenResponse> {
  const headers = new Headers({ Accept: 'application/json' });
  const token = accessTokenStore.get();
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const response = await fetch(`${apiBaseUrl}/me/playback-sessions/${sessionId}/token`, {
    method: 'POST',
    credentials: 'include',
    headers,
  });
  if (!response.ok) throw await problemFromResponse(response);
  return await response.json() as MediaTokenResponse;
}

export const streamingApi: StreamingApi = {
  async restoreSession() {
    try {
      return await refreshSession();
    } catch (error) {
      accessTokenStore.clear();
      if (error instanceof ProblemError && error.problem.status === 401) return null;
      throw error;
    }
  },

  async login(credentials) {
    const tokens = await call(async () => (await generatedLogin({
      body: credentials,
      throwOnError: true,
    })).data);
    return remember(tokens);
  },

  async register(credentials) {
    const tokens = await call(async () => (await generatedRegister({
      body: credentials,
      throwOnError: true,
    })).data);
    return remember(tokens);
  },

  async logout() {
    try {
      await call(() => generatedLogout({ throwOnError: true }));
    } finally {
      accessTokenStore.clear();
    }
  },

  catalogHome: () => call(async () => (await generatedCatalogHome({
    throwOnError: true,
  })).data),

  search: (query, page = 0, size = 20) => call(async () => (await generatedSearchMovies({
    query: { q: query, page, size },
    throwOnError: true,
  })).data),

  movieBySlug: (slug) => call(async () => (await generatedMovieBySlug({
    path: { slug },
    throwOnError: true,
  })).data),

  watchlist: (page = 0, size = 20) => withAuthRetry(async () => (await generatedWatchlist({
    query: { page, size },
    throwOnError: true,
  })).data),

  addToWatchlist: (movieId) => withAuthRetry(async () => {
    await generatedAddToWatchlist({
      path: { movieId },
      throwOnError: true,
    });
  }),

  removeFromWatchlist: (movieId) => withAuthRetry(async () => {
    await generatedRemoveFromWatchlist({
      path: { movieId },
      throwOnError: true,
    });
  }),

  playback: (movieId) => withAuthRetry(async () => (await generatedPlayback({
    path: { movieId },
    throwOnError: true,
  })).data),

  updateProgress: (movieId, progress) => withAuthRetry(async () => (await generatedUpdateProgress({
    body: progress,
    path: { movieId },
    throwOnError: true,
  })).data),

  mediaToken: (sessionId) => withAuthRetry(() => requestMediaToken(sessionId)),
};
