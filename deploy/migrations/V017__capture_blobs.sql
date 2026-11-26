-- Request bodies stored once per member: each repeated part of a captured body
-- (system, tools, every message) is one piece; request_details keeps a manifest.
-- Rows stay inline (body_format 1) until CaptureCompactor verifies an exact
-- rebuild from these pieces. Nothing here deletes captured data.
CREATE TABLE IF NOT EXISTS capture_blobs (
  member_id VARCHAR(36) NOT NULL,
  blob_key VARCHAR(64) NOT NULL,
  data TEXT NOT NULL,
  chars INTEGER NOT NULL,
  first_seen TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (member_id, blob_key)
);
ALTER TABLE request_details ADD COLUMN IF NOT EXISTS body_manifest TEXT;
ALTER TABLE request_details ADD COLUMN IF NOT EXISTS body_chars INTEGER;
ALTER TABLE request_details ADD COLUMN IF NOT EXISTS body_format SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE request_details ADD COLUMN IF NOT EXISTS body_check SMALLINT;
CREATE INDEX IF NOT EXISTS request_details_inline_bodies ON request_details(body_check, started_at) WHERE body_format=1;
