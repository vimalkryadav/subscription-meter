CREATE TABLE IF NOT EXISTS members (
  id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL,
  key_hash VARCHAR(64) NOT NULL UNIQUE, key_prefix VARCHAR(16) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE, rpm INTEGER NOT NULL, concurrency_limit INTEGER NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS requests (
  id VARCHAR(36) PRIMARY KEY, member_id VARCHAR(36) NOT NULL REFERENCES members(id),
  started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  path VARCHAR(80) NOT NULL, model VARCHAR(200) NOT NULL,
  state VARCHAR(32) NOT NULL DEFAULT 'pending', status_code INTEGER,
  duration_ms BIGINT, upstream_request_id VARCHAR(200), usage_seen BOOLEAN NOT NULL DEFAULT FALSE,
  input_tokens BIGINT NOT NULL DEFAULT 0, output_tokens BIGINT NOT NULL DEFAULT 0,
  cache_creation_input_tokens BIGINT NOT NULL DEFAULT 0, cache_read_input_tokens BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS requests_member_time ON requests(member_id, started_at);
CREATE INDEX IF NOT EXISTS requests_time ON requests(started_at);
CREATE TABLE IF NOT EXISTS request_details (
  request_id VARCHAR(36) NOT NULL, started_at TIMESTAMP WITH TIME ZONE NOT NULL,
  member_id VARCHAR(36) NOT NULL, upstream_mode VARCHAR(20) NOT NULL,
  method VARCHAR(16) NOT NULL, path VARCHAR(80) NOT NULL, query_string TEXT,
  peer_address VARCHAR(200), protocol VARCHAR(40), request_headers TEXT, request_body TEXT,
  response_headers TEXT, response_body TEXT, capture_state VARCHAR(32) NOT NULL DEFAULT 'pending',
  request_bytes BIGINT, response_bytes BIGINT, captured_bytes BIGINT, event_count INTEGER NOT NULL DEFAULT 0,
  truncated BOOLEAN NOT NULL DEFAULT FALSE, upstream_headers_ms BIGINT, first_byte_ms BIGINT,
  error_type VARCHAR(200), error_message TEXT, PRIMARY KEY (request_id, started_at)
);
CREATE INDEX IF NOT EXISTS details_member_time ON request_details(member_id,started_at);
CREATE TABLE IF NOT EXISTS request_events (
  request_id VARCHAR(36) NOT NULL, request_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
  observed_at TIMESTAMP WITH TIME ZONE NOT NULL, sequence INTEGER NOT NULL, elapsed_ms BIGINT NOT NULL,
  event_type VARCHAR(100) NOT NULL, event_data TEXT NOT NULL, truncated BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (request_id,sequence,observed_at)
);
CREATE INDEX IF NOT EXISTS events_request_sequence ON request_events(request_id,sequence);
CREATE TABLE IF NOT EXISTS gateway_access (
  request_id VARCHAR(36) NOT NULL, started_at TIMESTAMP WITH TIME ZONE NOT NULL, member_id VARCHAR(36),
  method VARCHAR(16), path VARCHAR(512), status_code INTEGER, duration_ms BIGINT,
  peer_address VARCHAR(200), protocol VARCHAR(40), request_headers TEXT, query_string TEXT,
  PRIMARY KEY(request_id,started_at)
);
