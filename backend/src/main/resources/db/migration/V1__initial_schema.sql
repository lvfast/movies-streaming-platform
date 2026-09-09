CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE app_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(32) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT app_user_username_format CHECK (username ~ '^[A-Za-z0-9_]{3,32}$')
);

CREATE UNIQUE INDEX uq_app_user_username_lower ON app_user (lower(username));

CREATE TABLE refresh_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by UUID REFERENCES refresh_session(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);

CREATE INDEX ix_refresh_session_user ON refresh_session (user_id);
CREATE INDEX ix_refresh_session_family ON refresh_session (family_id);

CREATE TABLE movie (
    id UUID PRIMARY KEY,
    slug VARCHAR(120) NOT NULL UNIQUE,
    title VARCHAR(200) NOT NULL,
    synopsis TEXT NOT NULL,
    release_year SMALLINT NOT NULL CHECK (release_year BETWEEN 1888 AND 2200),
    runtime_seconds INTEGER NOT NULL CHECK (runtime_seconds > 0),
    maturity_rating VARCHAR(16) NOT NULL,
    poster_url TEXT NOT NULL,
    backdrop_url TEXT NOT NULL,
    hls_manifest_url TEXT,
    featured BOOLEAN NOT NULL DEFAULT false,
    published BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    search_vector TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(synopsis, ''))
    ) STORED
);

CREATE INDEX ix_movie_search_vector ON movie USING GIN (search_vector);
CREATE INDEX ix_movie_title_trgm ON movie USING GIN (title gin_trgm_ops);
CREATE INDEX ix_movie_published_year ON movie (published, release_year DESC);

CREATE TABLE genre (
    id SMALLSERIAL PRIMARY KEY,
    slug VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL UNIQUE
);

CREATE TABLE movie_genre (
    movie_id UUID NOT NULL REFERENCES movie(id) ON DELETE CASCADE,
    genre_id SMALLINT NOT NULL REFERENCES genre(id) ON DELETE CASCADE,
    PRIMARY KEY (movie_id, genre_id)
);

CREATE TABLE watchlist (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    movie_id UUID NOT NULL REFERENCES movie(id) ON DELETE CASCADE,
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, movie_id)
);

CREATE INDEX ix_watchlist_user_added ON watchlist (user_id, added_at DESC);

CREATE TABLE viewing_progress (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    movie_id UUID NOT NULL REFERENCES movie(id) ON DELETE CASCADE,
    position_seconds INTEGER NOT NULL CHECK (position_seconds >= 0),
    duration_seconds INTEGER NOT NULL CHECK (duration_seconds > 0),
    completed BOOLEAN NOT NULL DEFAULT false,
    client_updated_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, movie_id),
    CONSTRAINT progress_position_lte_duration CHECK (position_seconds <= duration_seconds)
);

CREATE INDEX ix_viewing_progress_user_updated ON viewing_progress (user_id, updated_at DESC);

CREATE TABLE catalog_import (
    manifest_version VARCHAR(80) PRIMARY KEY,
    manifest_sha256 CHAR(64) NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
