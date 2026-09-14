import {
  adminAbortUpload as generatedAdminAbortUpload,
  adminActivateVersion as generatedAdminActivateVersion,
  adminArchiveMovie as generatedAdminArchiveMovie,
  adminAttachArtwork as generatedAdminAttachArtwork,
  adminCompleteUpload as generatedAdminCompleteUpload,
  adminCreateMovie as generatedAdminCreateMovie,
  adminCreateUpload as generatedAdminCreateUpload,
  adminGetJob as generatedAdminGetJob,
  adminGetMovie as generatedAdminGetMovie,
  adminGetUpload as generatedAdminGetUpload,
  adminListAssets as generatedAdminListAssets,
  adminListAudit as generatedAdminListAudit,
  adminListGenres as generatedAdminListGenres,
  adminListJobs as generatedAdminListJobs,
  adminListMovies as generatedAdminListMovies,
  adminListUploadParts as generatedAdminListUploadParts,
  adminListVersions as generatedAdminListVersions,
  adminPreviewAsset as generatedAdminPreviewAsset,
  adminPreviewMovie as generatedAdminPreviewMovie,
  adminPublishMovie as generatedAdminPublishMovie,
  adminRestoreMovie as generatedAdminRestoreMovie,
  adminRetryJob as generatedAdminRetryJob,
  adminSignUploadParts as generatedAdminSignUploadParts,
  adminUnpublishMovie as generatedAdminUnpublishMovie,
  adminUpdateMovie as generatedAdminUpdateMovie,
  refreshPreviewMediaToken as generatedRefreshPreviewMediaToken,
} from './generated';
import type {
  AdminMovie,
  AdminMoviePage,
  AssetPreview,
  ArtworkRequest,
  AuditEventPage,
  GenreOption,
  JobPage,
  JobView,
  ManagedPlayback,
  MediaAssetPage,
  MediaToken,
  MediaVersionPage,
  MovieInput,
  Problem,
  PublishRequest,
  SignedPart,
  UploadInput,
  UploadSession,
  UploadedPartPage,
} from './generated/types.gen';
import { ProblemError, withAuthRetry } from './streaming-api';

export type { AdminMovie, AdminMoviePage, MovieInput } from './generated/types.gen';
export type {
  AssetPreview,
  ArtworkRequest,
  AuditEvent,
  AuditEventPage,
  JobPage,
  JobView,
  ManagedPlayback,
  MediaAsset,
  MediaAssetPage,
  MediaVersion,
  MediaVersionPage,
  PublishRequest,
  SignedPart,
  UploadInput,
  UploadSession,
  UploadedPart,
} from './generated/types.gen';

export type Genre = GenreOption;

export type AdminMovieQuery = {
  page?: number;
  size?: number;
};

export type AdminAuditQuery = {
  action?: string;
  entityType?: string;
  entityId?: string;
  page?: number;
  size?: number;
};

export type Versioned<T> = { data: T; revision: number };

export class RevisionConflictError extends ProblemError {
  constructor(problem: Problem) {
    super(problem);
    this.name = 'RevisionConflictError';
  }
}

export interface AdminApi {
  listMovies(query: AdminMovieQuery, signal?: AbortSignal): Promise<AdminMoviePage>;
  getMovie(movieId: string, signal?: AbortSignal): Promise<Versioned<AdminMovie>>;
  createMovie(input: MovieInput, requestKey: string): Promise<Versioned<AdminMovie>>;
  updateMovie(movieId: string, input: MovieInput, revision: number): Promise<Versioned<AdminMovie>>;
  listGenres(signal?: AbortSignal): Promise<Genre[]>;

  createUpload(movieId: string, input: UploadInput, requestKey: string): Promise<UploadSession>;
  getUpload(uploadId: string, signal?: AbortSignal): Promise<UploadSession>;
  listUploadParts(uploadId: string, marker?: number, signal?: AbortSignal): Promise<UploadedPartPage>;
  signUploadParts(uploadId: string, partNumbers: number[]): Promise<SignedPart[]>;
  completeUpload(uploadId: string, requestKey: string): Promise<UploadSession>;
  abortUpload(uploadId: string): Promise<UploadSession>;

  listVersions(
    movieId: string,
    query?: AdminMovieQuery,
    signal?: AbortSignal,
  ): Promise<MediaVersionPage>;
  listAssets(
    movieId: string,
    query?: AdminMovieQuery,
    signal?: AbortSignal,
  ): Promise<MediaAssetPage>;
  previewAsset(assetId: string): Promise<AssetPreview>;
  attachArtwork(
    movieId: string,
    input: ArtworkRequest,
    revision: number,
  ): Promise<Versioned<AdminMovie>>;

  listJobs(query?: AdminMovieQuery, signal?: AbortSignal): Promise<JobPage>;
  getJob(jobId: string, signal?: AbortSignal): Promise<JobView>;
  retryJob(jobId: string, requestKey: string): Promise<JobView>;

  previewMovie(movieId: string, mediaVersionId?: string): Promise<ManagedPlayback>;
  previewToken(sessionId: string): Promise<MediaToken>;
  publishMovie(
    movieId: string,
    input: PublishRequest,
    revision: number,
    requestKey: string,
  ): Promise<Versioned<AdminMovie>>;
  activateVersion(
    movieId: string,
    input: PublishRequest,
    revision: number,
    requestKey: string,
  ): Promise<Versioned<AdminMovie>>;
  unpublishMovie(movieId: string, revision: number): Promise<Versioned<AdminMovie>>;
  archiveMovie(movieId: string, revision: number): Promise<Versioned<AdminMovie>>;
  restoreMovie(movieId: string, revision: number): Promise<Versioned<AdminMovie>>;

  listAudit(query: AdminAuditQuery, signal?: AbortSignal): Promise<AuditEventPage>;
}

function revisionFromEtag(response: Response): number {
  const etag = response.headers.get('etag');
  // Nginx weakens the ETag to W/"<revision>" when it gzips the JSON response, so the parser
  // accepts the optional weak prefix in addition to the strong quoted form.
  const match = etag ? /^(?:W\/)?"?(\d+)"?$/i.exec(etag.trim()) : null;
  if (!match) {
    throw new ProblemError({
      type: 'about:blank',
      title: 'Service unavailable',
      status: 0,
      detail: 'The movie revision could not be read from the response.',
      requestId: 'unavailable',
      code: 'ETAG_MISSING',
    });
  }
  return Number(match[1]);
}

function toVersioned(response: Response, data: AdminMovie): Versioned<AdminMovie> {
  return { data, revision: revisionFromEtag(response) };
}

function isStaleRevision(error: unknown): error is ProblemError {
  return error instanceof ProblemError && error.problem.status === 412;
}

function staleAware<T>(request: () => Promise<T>): Promise<T> {
  return withAuthRetry(request).catch((error) => {
    if (isStaleRevision(error)) throw new RevisionConflictError(error.problem);
    throw error;
  });
}

export const adminApi: AdminApi = {
  listMovies: (query, signal) => withAuthRetry(async () => (await generatedAdminListMovies({
    query: { page: query.page, size: query.size },
    signal,
    throwOnError: true,
  })).data),

  getMovie: (movieId, signal) => withAuthRetry(async () => {
    const { data, response } = await generatedAdminGetMovie({
      path: { movieId },
      signal,
      throwOnError: true,
    });
    return toVersioned(response, data);
  }),

  createMovie: (input, requestKey) => withAuthRetry(async () => {
    const { data, response } = await generatedAdminCreateMovie({
      body: input,
      headers: { 'Idempotency-Key': requestKey },
      throwOnError: true,
    });
    return toVersioned(response, data);
  }),

  updateMovie: (movieId, input, revision) => staleAware(async () => {
    const { data, response } = await generatedAdminUpdateMovie({
      body: input,
      path: { movieId },
      headers: { 'If-Match': `"${revision}"` },
      throwOnError: true,
    });
    return toVersioned(response, data);
  }),

  listGenres: (signal) => withAuthRetry(async () => (await generatedAdminListGenres({
    signal,
    throwOnError: true,
  })).data.items),

  createUpload: (movieId, input, requestKey) => withAuthRetry(async () => (await generatedAdminCreateUpload({
    body: input,
    path: { movieId },
    headers: { 'Idempotency-Key': requestKey },
    throwOnError: true,
  })).data),

  getUpload: (uploadId, signal) => withAuthRetry(async () => (await generatedAdminGetUpload({
    path: { uploadId },
    signal,
    throwOnError: true,
  })).data),

  listUploadParts: (uploadId, marker, signal) => withAuthRetry(async () => (await generatedAdminListUploadParts({
    path: { uploadId },
    query: marker == null ? {} : { marker },
    signal,
    throwOnError: true,
  })).data),

  signUploadParts: (uploadId, partNumbers) => withAuthRetry(async () => (await generatedAdminSignUploadParts({
    body: { partNumbers },
    path: { uploadId },
    throwOnError: true,
  })).data.items),

  completeUpload: (uploadId, requestKey) => withAuthRetry(async () => (await generatedAdminCompleteUpload({
    path: { uploadId },
    headers: { 'Idempotency-Key': requestKey },
    throwOnError: true,
  })).data),

  abortUpload: (uploadId) => withAuthRetry(async () => (await generatedAdminAbortUpload({
    path: { uploadId },
    throwOnError: true,
  })).data),

  listVersions: (movieId, query = {}, signal) => withAuthRetry(async () => (await generatedAdminListVersions({
    path: { movieId },
    query: { page: query.page, size: query.size },
    signal,
    throwOnError: true,
  })).data),

  listAssets: (movieId, query = {}, signal) => withAuthRetry(async () => (await generatedAdminListAssets({
    path: { movieId },
    query: { page: query.page, size: query.size },
    signal,
    throwOnError: true,
  })).data),

  previewAsset: (assetId) => withAuthRetry(async () => (await generatedAdminPreviewAsset({
    path: { assetId },
    throwOnError: true,
  })).data),

  attachArtwork: (movieId, input, revision) => staleAware(async () => {
    const { data, response } = await generatedAdminAttachArtwork({
      body: input,
      path: { movieId },
      headers: { 'If-Match': `"${revision}"` },
      throwOnError: true,
    });
    return toVersioned(response, data);
  }),

  listJobs: (query = {}, signal) => withAuthRetry(async () => (await generatedAdminListJobs({
    query: { page: query.page, size: query.size },
    signal,
    throwOnError: true,
  })).data),

  getJob: (jobId, signal) => withAuthRetry(async () => (await generatedAdminGetJob({
    path: { jobId },
    signal,
    throwOnError: true,
  })).data),

  retryJob: (jobId, requestKey) => withAuthRetry(async () => (await generatedAdminRetryJob({
    path: { jobId },
    headers: { 'Idempotency-Key': requestKey },
    throwOnError: true,
  })).data),

  previewMovie: (movieId, mediaVersionId) => withAuthRetry(async () => (await generatedAdminPreviewMovie({
    path: { movieId },
    ...(mediaVersionId ? { body: { mediaVersionId } } : {}),
    throwOnError: true,
  })).data),

  previewToken: (sessionId) => withAuthRetry(async () => (await generatedRefreshPreviewMediaToken({
    path: { sessionId },
    throwOnError: true,
  })).data),

  publishMovie: (movieId, input, revision, requestKey) => staleAware(async () => {
    const { data, response } = await generatedAdminPublishMovie({
      body: input,
      path: { movieId },
      headers: { 'If-Match': `"${revision}"`, 'Idempotency-Key': requestKey },
      throwOnError: true,
    });
    return toVersioned(response, data);
  }),

  activateVersion: (movieId, input, revision, requestKey) => staleAware(async () => {
    const { data, response } = await generatedAdminActivateVersion({
      body: input,
      path: { movieId },
      headers: { 'If-Match': `"${revision}"`, 'Idempotency-Key': requestKey },
      throwOnError: true,
    });
    return toVersioned(response, data);
  }),

  unpublishMovie: (movieId, revision) => staleAware(async () => {
    const { data, response } = await generatedAdminUnpublishMovie({
      path: { movieId },
      headers: { 'If-Match': `"${revision}"` },
      throwOnError: true,
    });
    return toVersioned(response, data);
  }),

  archiveMovie: (movieId, revision) => staleAware(async () => {
    const { data, response } = await generatedAdminArchiveMovie({
      path: { movieId },
      headers: { 'If-Match': `"${revision}"` },
      throwOnError: true,
    });
    return toVersioned(response, data);
  }),

  restoreMovie: (movieId, revision) => staleAware(async () => {
    const { data, response } = await generatedAdminRestoreMovie({
      path: { movieId },
      headers: { 'If-Match': `"${revision}"` },
      throwOnError: true,
    });
    return toVersioned(response, data);
  }),

  listAudit: (query, signal) => withAuthRetry(async () => (await generatedAdminListAudit({
    query: {
      action: query.action,
      entityType: query.entityType,
      entityId: query.entityId,
      page: query.page,
      size: query.size,
    },
    signal,
    throwOnError: true,
  })).data),
};
