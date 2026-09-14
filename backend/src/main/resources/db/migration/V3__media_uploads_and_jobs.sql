-- Media uploads and durable processing jobs. Additive only; V1__initial_schema.sql
-- and V2__admin_foundation.sql are never edited.
--
-- A video upload targets a new media_version; poster/backdrop uploads target a new
-- media_asset. An upload_session records the multipart upload progress and the job it
-- produced. Exactly one active (nonterminal) replacement is allowed per movie for video
-- and per movie/kind for artwork.

CREATE TABLE media_version (
    id UUID PRIMARY KEY,
    movie_id UUID NOT NULL REFERENCES movie(id),
    state VARCHAR(16) NOT NULL
        CHECK (state IN ('UPLOADING', 'QUEUED', 'PROCESSING', 'READY', 'FAILED', 'ABORTED')),
    source_key VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_media_version_movie ON media_version (movie_id, created_at DESC);

-- A movie has at most one nonterminal video replacement.
CREATE UNIQUE INDEX uq_media_version_nonterminal ON media_version (movie_id)
    WHERE state IN ('UPLOADING', 'QUEUED', 'PROCESSING');

CREATE TABLE media_asset (
    id UUID PRIMARY KEY,
    movie_id UUID NOT NULL REFERENCES movie(id),
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('POSTER', 'BACKDROP')),
    state VARCHAR(16) NOT NULL
        CHECK (state IN ('UPLOADING', 'STORED', 'PROCESSING', 'READY', 'FAILED', 'ABORTED')),
    source_key VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_media_asset_movie ON media_asset (movie_id, kind, created_at DESC);

-- A movie has at most one nonterminal artwork asset per kind.
CREATE UNIQUE INDEX uq_media_asset_nonterminal ON media_asset (movie_id, kind)
    WHERE state IN ('UPLOADING', 'STORED', 'PROCESSING');

CREATE TABLE media_job (
    id UUID PRIMARY KEY,
    movie_id UUID NOT NULL REFERENCES movie(id),
    media_version_id UUID REFERENCES media_version(id),
    asset_id UUID REFERENCES media_asset(id),
    kind VARCHAR(12) NOT NULL CHECK (kind IN ('TRANSCODE', 'ARTWORK')),
    state VARCHAR(16) NOT NULL
        CHECK (state IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED')),
    attempt_number INTEGER NOT NULL DEFAULT 0 CHECK (attempt_number >= 0),
    progress_percent INTEGER NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
    stage VARCHAR(32),
    error_code VARCHAR(64),
    error_summary TEXT,
    retry_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT media_job_version_or_asset CHECK (
        (kind = 'TRANSCODE' AND media_version_id IS NOT NULL AND asset_id IS NULL)
        OR (kind = 'ARTWORK' AND asset_id IS NOT NULL AND media_version_id IS NULL)
    )
);

CREATE INDEX ix_media_job_movie ON media_job (movie_id, created_at DESC);

-- At most one active (nonterminal) job per version and per asset.
CREATE UNIQUE INDEX uq_media_job_active_transcode ON media_job (media_version_id)
    WHERE state IN ('QUEUED', 'RUNNING', 'RETRY_WAIT');
CREATE UNIQUE INDEX uq_media_job_active_artwork ON media_job (asset_id)
    WHERE state IN ('QUEUED', 'RUNNING', 'RETRY_WAIT');

CREATE TABLE media_job_attempt (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES media_job(id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    worker_id VARCHAR(128) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    lease_until TIMESTAMPTZ NOT NULL,
    output_prefix VARCHAR(512),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    CONSTRAINT uq_media_job_attempt UNIQUE (job_id, attempt_number)
);

CREATE INDEX ix_media_job_attempt_job ON media_job_attempt (job_id, attempt_number);

CREATE TABLE upload_session (
    id UUID PRIMARY KEY,
    movie_id UUID NOT NULL REFERENCES movie(id),
    media_version_id UUID REFERENCES media_version(id),
    asset_id UUID REFERENCES media_asset(id),
    job_id UUID REFERENCES media_job(id),
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('VIDEO', 'POSTER', 'BACKDROP')),
    state VARCHAR(16) NOT NULL
        CHECK (state IN ('OPEN', 'COMPLETING', 'COMPLETED', 'ABORTED', 'EXPIRED', 'FAILED')),
    object_key VARCHAR(512) NOT NULL,
    storage_upload_id VARCHAR(200),
    content_type VARCHAR(200) NOT NULL,
    part_size_bytes BIGINT NOT NULL CHECK (part_size_bytes > 0),
    total_parts INTEGER NOT NULL CHECK (total_parts > 0),
    declared_bytes BIGINT NOT NULL CHECK (declared_bytes > 0),
    resume_fingerprint VARCHAR(80) NOT NULL
        CHECK (resume_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT upload_session_version_or_asset CHECK (
        (kind = 'VIDEO' AND media_version_id IS NOT NULL AND asset_id IS NULL)
        OR (kind IN ('POSTER', 'BACKDROP') AND asset_id IS NOT NULL AND media_version_id IS NULL)
    )
);

CREATE INDEX ix_upload_session_movie ON upload_session (movie_id, created_at DESC);
CREATE INDEX ix_upload_session_expired ON upload_session (expires_at) WHERE state = 'OPEN';

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(64) NOT NULL CHECK (event_type IN ('transcode.requested.v1', 'artwork.requested.v1')),
    job_id UUID NOT NULL REFERENCES media_job(id),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX ix_outbox_event_pending ON outbox_event (created_at) WHERE published_at IS NULL;

CREATE TABLE inbox_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(64) NOT NULL,
    job_id UUID NOT NULL REFERENCES media_job(id),
    attempt_id UUID REFERENCES media_job_attempt(id),
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    payload JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);

CREATE INDEX ix_inbox_event_job_sequence ON inbox_event (job_id, sequence);
