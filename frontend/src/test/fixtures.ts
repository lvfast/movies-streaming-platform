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
import type { ManagedPlayback } from '../api/streaming-api';
import type {
  AdminApi,
  AdminMovie,
  AdminMoviePage,
  AssetPreview,
  AuditEvent,
  AuditEventPage,
  Genre,
  JobPage,
  JobView,
  MediaAsset,
  MediaAssetPage,
  MediaVersion,
  MediaVersionPage,
  SignedPart,
  UploadSession,
  UploadedPart,
} from '../api/admin-api';
import type { UploadedPartPage } from '../api/generated/types.gen';
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
  roles: ['USER'],
};

export const adminUser: User = {
  id: '90000000-0000-0000-0000-000000000001',
  username: 'admin_one',
  roles: ['ADMIN'],
};

export const adminMovie: AdminMovie = {
  id: '00000000-0000-0000-0000-000000000001',
  title: 'Starlight Archive',
  slug: 'starlight-archive',
  synopsis: 'An archivist follows a signal hidden in a collection of star charts.',
  releaseYear: 2026,
  maturityRating: 'PG',
  genreIds: [1, 2],
  featured: false,
  lifecycle: 'DRAFT',
  revision: 7,
  managementMode: 'MANAGED',
  activeMediaVersionId: null,
  posterAssetId: null,
  backdropAssetId: null,
  runtimeSeconds: null,
  firstPublishedAt: null,
  createdAt: '2026-09-10T00:00:00.000Z',
  updatedAt: '2026-09-10T00:00:00.000Z',
};

export const adminPage: AdminMoviePage = {
  items: [adminMovie],
  page: 0,
  size: 20,
  total: 1,
};

export const genres: Genre[] = [
  { id: 1, slug: 'adventure', name: 'Adventure' },
  { id: 2, slug: 'science-fiction', name: 'Science Fiction' },
];

export const authTokens: AuthTokens = {
  accessToken: 'memory-only-token',
  tokenType: 'Bearer',
  expiresIn: 900,
  user,
};

export const adminAuthTokens: AuthTokens = {
  ...authTokens,
  user: adminUser,
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

export const managedPlayback: ManagedPlayback = {
  movieId: featuredMovie.id,
  manifestUrl: 'https://media.example.test/hls/00000000-0000-0000-0000-000000000001/20000000-0000-0000-0000-000000000001/30000000-0000-0000-0000-000000000001/index.m3u8',
  resumePositionSeconds: 12,
  sessionId: '40000000-0000-0000-0000-000000000001',
  mediaVersionId: '20000000-0000-0000-0000-000000000001',
  mediaToken: 'initial-media-token',
  mediaTokenExpiresAt: '2099-01-01T00:00:00.000Z',
};

/**
 * The shape the backend actually answers: a root-relative HLS path that has to be
 * joined onto the configured media origin by the player.
 */
export const managedPlaybackWithRelativeManifest: ManagedPlayback = {
  ...managedPlayback,
  manifestUrl: '/hls/00000000-0000-0000-0000-000000000001/20000000-0000-0000-0000-000000000001/30000000-0000-0000-0000-000000000001/index.m3u8',
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
    mediaToken: vi.fn().mockResolvedValue({
      mediaToken: 'renewed-media-token',
      mediaTokenExpiresAt: '2099-01-01T00:00:00.000Z',
    }),
    ...overrides,
  };
}

export const uploadSession: UploadSession = {
  id: '50000000-0000-0000-0000-000000000001',
  movieId: adminMovie.id,
  mediaVersionId: '20000000-0000-0000-0000-000000000001',
  assetId: null,
  kind: 'VIDEO',
  state: 'OPEN',
  partSizeBytes: 8_388_608,
  totalParts: 2,
  declaredBytes: 9_000_000,
  expiresAt: '2099-01-01T00:00:00.000Z',
  jobId: null,
};

export const completedUploadSession: UploadSession = {
  ...uploadSession,
  state: 'COMPLETED',
  jobId: '60000000-0000-0000-0000-000000000001',
};

export const uploadedPart: UploadedPart = {
  partNumber: 1,
  etag: 'etag-1',
  sizeBytes: 8_388_608,
};

export const uploadedPartPage: UploadedPartPage = { items: [uploadedPart], nextMarker: null };

export const signedPart: SignedPart = {
  partNumber: 1,
  url: 'https://storage.example.test/part-1',
  expiresAt: '2099-01-01T00:00:00.000Z',
  headers: {},
};

export const mediaVersion: MediaVersion = {
  id: '20000000-0000-0000-0000-000000000001',
  movieId: adminMovie.id,
  state: 'READY',
  createdAt: '2026-09-10T00:00:00.000Z',
  updatedAt: '2026-09-10T00:00:00.000Z',
};

export const mediaVersionPage: MediaVersionPage = {
  items: [mediaVersion],
  page: 0,
  size: 20,
  total: 1,
};

export const mediaAsset: MediaAsset = {
  id: '70000000-0000-0000-0000-000000000001',
  movieId: adminMovie.id,
  kind: 'POSTER',
  state: 'READY',
  createdAt: '2026-09-10T00:00:00.000Z',
  updatedAt: '2026-09-10T00:00:00.000Z',
};

export const mediaAssetPage: MediaAssetPage = {
  items: [mediaAsset],
  page: 0,
  size: 20,
  total: 1,
};

export const assetPreview: AssetPreview = {
  url: 'https://storage.example.test/artwork/image.jpg?X-Amz-Expires=60',
  expiresAt: '2099-01-01T00:00:00.000Z',
};

export const job: JobView = {
  id: '60000000-0000-0000-0000-000000000001',
  movieId: adminMovie.id,
  mediaVersionId: mediaVersion.id,
  assetId: null,
  kind: 'TRANSCODE',
  state: 'RUNNING',
  attemptNumber: 1,
  progressPercent: 40,
  stage: 'ENCODING',
  errorCode: null,
  errorSummary: null,
  retryAt: null,
  updatedAt: '2026-09-10T00:00:00.000Z',
};

export const jobPage: JobPage = { items: [job], page: 0, size: 20, total: 1 };

export const auditEvent: AuditEvent = {
  id: 1,
  actorId: adminUser.id,
  actorType: 'USER',
  action: 'MOVIE_PUBLISHED',
  entityType: 'MOVIE',
  entityId: adminMovie.id,
  requestId: 'request-1',
  before: { lifecycle: 'DRAFT' },
  after: { lifecycle: 'PUBLISHED' },
  createdAt: '2026-09-10T00:00:00.000Z',
};

export const auditPage: AuditEventPage = { items: [auditEvent], page: 0, size: 20, total: 1 };

export const previewPlayback: ManagedPlayback = {
  ...managedPlayback,
  sessionId: '40000000-0000-0000-0000-000000000009',
  mediaToken: 'preview-media-token',
};

export function createAdminApi(overrides: Partial<AdminApi> = {}): AdminApi {
  return {
    listMovies: vi.fn().mockResolvedValue(adminPage),
    getMovie: vi.fn().mockResolvedValue({ data: adminMovie, revision: adminMovie.revision }),
    createMovie: vi.fn().mockResolvedValue({ data: adminMovie, revision: 1 }),
    updateMovie: vi.fn().mockResolvedValue({ data: adminMovie, revision: adminMovie.revision + 1 }),
    listGenres: vi.fn().mockResolvedValue(genres),
    createUpload: vi.fn().mockResolvedValue(uploadSession),
    getUpload: vi.fn().mockResolvedValue(uploadSession),
    listUploadParts: vi.fn().mockResolvedValue({ items: [], nextMarker: null }),
    signUploadParts: vi.fn().mockResolvedValue([]),
    completeUpload: vi.fn().mockResolvedValue(completedUploadSession),
    abortUpload: vi.fn().mockResolvedValue({ ...uploadSession, state: 'ABORTED' }),
    listVersions: vi.fn().mockResolvedValue(mediaVersionPage),
    listAssets: vi.fn().mockResolvedValue(mediaAssetPage),
    previewAsset: vi.fn().mockResolvedValue(assetPreview),
    attachArtwork: vi.fn().mockResolvedValue({ data: adminMovie, revision: adminMovie.revision + 1 }),
    listJobs: vi.fn().mockResolvedValue(jobPage),
    getJob: vi.fn().mockResolvedValue(job),
    retryJob: vi.fn().mockResolvedValue({ ...job, id: '60000000-0000-0000-0000-00000000000f', state: 'QUEUED' }),
    previewMovie: vi.fn().mockResolvedValue(previewPlayback),
    previewToken: vi.fn().mockResolvedValue({
      mediaToken: 'preview-renewed-token',
      mediaTokenExpiresAt: '2099-01-01T00:00:00.000Z',
    }),
    publishMovie: vi.fn().mockResolvedValue({ data: { ...adminMovie, lifecycle: 'PUBLISHED' }, revision: adminMovie.revision + 1 }),
    activateVersion: vi.fn().mockResolvedValue({ data: { ...adminMovie, lifecycle: 'PUBLISHED' }, revision: adminMovie.revision + 1 }),
    unpublishMovie: vi.fn().mockResolvedValue({ data: { ...adminMovie, lifecycle: 'UNPUBLISHED' }, revision: adminMovie.revision + 1 }),
    archiveMovie: vi.fn().mockResolvedValue({ data: { ...adminMovie, lifecycle: 'ARCHIVED' }, revision: adminMovie.revision + 1 }),
    restoreMovie: vi.fn().mockResolvedValue({ data: { ...adminMovie, lifecycle: 'UNPUBLISHED' }, revision: adminMovie.revision + 1 }),
    listAudit: vi.fn().mockResolvedValue(auditPage),
    ...overrides,
  };
}
