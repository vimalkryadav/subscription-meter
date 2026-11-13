-- Additive: legacy sessions retain their previous expiry behavior.
-- portal_sessions remains the revocable device/session anchor for admin tooling.
CREATE TABLE IF NOT EXISTS portal_refresh_families (
  session_hash VARCHAR(64) PRIMARY KEY REFERENCES portal_sessions(token_hash) ON DELETE CASCADE,
  csrf_token VARCHAR(64) NOT NULL
);
CREATE TABLE IF NOT EXISTS portal_access_tokens (
  token_hash VARCHAR(64) PRIMARY KEY,
  session_hash VARCHAR(64) NOT NULL REFERENCES portal_refresh_families(session_hash) ON DELETE CASCADE,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS portal_access_family ON portal_access_tokens(session_hash);
CREATE TABLE IF NOT EXISTS portal_refresh_tokens (
  token_hash VARCHAR(64) PRIMARY KEY,
  session_hash VARCHAR(64) NOT NULL REFERENCES portal_refresh_families(session_hash) ON DELETE CASCADE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  used_at TIMESTAMP WITH TIME ZONE,
  receipt TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS portal_refresh_family ON portal_refresh_tokens(session_hash);
