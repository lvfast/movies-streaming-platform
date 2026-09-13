-- Admin foundation: roles, audit, idempotent operations, managed movie columns and
-- a revision-keyed catalog cache. Additive only; V1__initial_schema.sql is never edited.

CREATE TABLE user_role (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT user_role_format CHECK (role ~ '^[A-Z]{2,32}$')
);

CREATE INDEX ix_user_role_role ON user_role (role);

CREATE TABLE audit_event (
    id BIGSERIAL PRIMARY KEY,
    actor_id UUID NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id UUID,
    request_id VARCHAR(100),
    before JSONB,
    after JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_audit_event_entity ON audit_event (entity_type, entity_id, created_at DESC);

CREATE TABLE operation_request (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    operation VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    status_code INTEGER NOT NULL,
    response_body JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_operation_request UNIQUE (actor_id, operation, idempotency_key)
);

-- Managed movie editorial columns. Legacy rows keep their existing publication state.
ALTER TABLE movie ADD COLUMN management_mode VARCHAR(8) NOT NULL DEFAULT 'LEGACY'
    CHECK (management_mode IN ('LEGACY', 'MANAGED'));
ALTER TABLE movie ADD COLUMN lifecycle VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED'
    CHECK (lifecycle IN ('DRAFT', 'PUBLISHED', 'UNPUBLISHED', 'ARCHIVED'));
ALTER TABLE movie ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE movie ADD COLUMN first_published_at TIMESTAMPTZ;
ALTER TABLE movie ADD COLUMN active_media_version_id UUID;
ALTER TABLE movie ADD COLUMN poster_asset_id UUID;
ALTER TABLE movie ADD COLUMN backdrop_asset_id UUID;

-- Managed drafts carry no runtime or server-owned media URLs yet.
ALTER TABLE movie ALTER COLUMN runtime_seconds DROP NOT NULL;
ALTER TABLE movie ALTER COLUMN poster_url DROP NOT NULL;
ALTER TABLE movie ALTER COLUMN backdrop_url DROP NOT NULL;

-- Single-row catalog revision used to namespace Redis cache keys so admin/import
-- mutations never require a wildcard KEYS scan.
CREATE TABLE catalog_revision (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    revision BIGINT NOT NULL DEFAULT 0
);

INSERT INTO catalog_revision (id, revision) VALUES (1, 0);
