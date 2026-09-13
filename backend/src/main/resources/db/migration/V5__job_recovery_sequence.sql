-- Processing reliability (P4). The result inbox is a dedupe log, but out-of-order deliveries must
-- not regress a RUNNING job: a worker publishes per-attempt events with a monotonic sequence, so the
-- job remembers the highest sequence already applied and ignores anything lower. No other schema
-- change is required: retry-wait state and lease expiry reuse existing columns (retry_at, state).
ALTER TABLE media_job ADD COLUMN last_sequence BIGINT NOT NULL DEFAULT 0
    CHECK (last_sequence >= 0);
