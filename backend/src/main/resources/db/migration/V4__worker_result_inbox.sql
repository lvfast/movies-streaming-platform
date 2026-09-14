-- Processing happy path (P3). The result inbox is a dedupe log: a late, duplicate or malformed
-- worker result may reference an attempt that is no longer (or never was) the current attempt and
-- must be recorded then ignored, not rejected at the storage layer. The current-attempt gate is
-- enforced in service logic, so the foreign key on attempt_id is too strict and is removed. No
-- other schema change is required for P3.
ALTER TABLE inbox_event DROP CONSTRAINT inbox_event_attempt_id_fkey;
