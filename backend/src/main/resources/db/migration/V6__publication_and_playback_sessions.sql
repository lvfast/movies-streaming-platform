-- Publication and protected playback (P5). The packet plan reserved the name
-- V4__media_playback_sessions.sql, but V4-V5 were already applied by the processing packets, so this
-- additive migration uses the next free number. Nothing already applied is edited.
--
-- Playback sessions pin a viewer to the READY media version that was active when the session was
-- created, so activating a replacement never disturbs an in-flight viewer. Viewing progress records
-- the session and version it belongs to; legacy progress rows keep allowing nulls.

CREATE TABLE media_playback_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    movie_id UUID NOT NULL REFERENCES movie(id) ON DELETE CASCADE,
    media_version_id UUID NOT NULL REFERENCES media_version(id),
    purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('VIEWER', 'PREVIEW')),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_media_playback_session_user ON media_playback_session (user_id, created_at DESC);

-- Verified runtime of a READY transcode, recorded when the artifact is accepted so publication can
-- project a runtime without re-reading the artifact manifest.
ALTER TABLE media_version ADD COLUMN duration_seconds INTEGER CHECK (duration_seconds > 0);

ALTER TABLE viewing_progress ADD COLUMN session_id UUID REFERENCES media_playback_session(id);
ALTER TABLE viewing_progress ADD COLUMN media_version_id UUID REFERENCES media_version(id);

-- Promotion log for validated artwork copied into the public artwork namespace. The pair
-- (media_asset, kind) is promoted at most once; a repeated promotion is a no-op copy.
CREATE TABLE media_publication (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    movie_id UUID NOT NULL REFERENCES movie(id) ON DELETE CASCADE,
    media_asset_id UUID NOT NULL REFERENCES media_asset(id),
    asset_kind VARCHAR(8) NOT NULL CHECK (asset_kind IN ('POSTER', 'BACKDROP')),
    source_key VARCHAR(512) NOT NULL,
    public_key VARCHAR(512) NOT NULL,
    promoted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_media_publication_asset UNIQUE (media_asset_id, asset_kind)
);

CREATE INDEX ix_media_publication_movie ON media_publication (movie_id, promoted_at DESC);
