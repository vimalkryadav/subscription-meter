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
ALTER TABLE members ADD COLUMN IF NOT EXISTS key_sealed TEXT;
ALTER TABLE requests ADD COLUMN IF NOT EXISTS provider VARCHAR(32) NOT NULL DEFAULT 'anthropic';
ALTER TABLE requests ADD COLUMN IF NOT EXISTS upstream_account VARCHAR(80) NOT NULL DEFAULT 'primary';
ALTER TABLE requests ADD COLUMN IF NOT EXISTS client_family VARCHAR(40) NOT NULL DEFAULT 'unknown';
ALTER TABLE requests ADD COLUMN IF NOT EXISTS member_key_prefix VARCHAR(16);
ALTER TABLE requests ADD COLUMN IF NOT EXISTS usage_components TEXT;
ALTER TABLE requests ADD COLUMN IF NOT EXISTS usage_complete BOOLEAN NOT NULL DEFAULT FALSE;
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
CREATE TABLE IF NOT EXISTS portal_accounts (
  id VARCHAR(36) PRIMARY KEY, username VARCHAR(40) NOT NULL UNIQUE,
  display_name VARCHAR(100) NOT NULL, password_hash VARCHAR(300) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE portal_accounts ADD COLUMN IF NOT EXISTS role VARCHAR(10) NOT NULL DEFAULT 'member';
CREATE TABLE IF NOT EXISTS portal_sessions (
  token_hash VARCHAR(64) PRIMARY KEY, account_id VARCHAR(36) NOT NULL REFERENCES portal_accounts(id),
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL, last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_account ON portal_sessions(account_id);
CREATE TABLE IF NOT EXISTS portal_key_requests (
  id VARCHAR(36) PRIMARY KEY, account_id VARCHAR(36) NOT NULL UNIQUE REFERENCES portal_accounts(id),
  state VARCHAR(20) NOT NULL, reason VARCHAR(500) NOT NULL,
  rpm INTEGER NOT NULL DEFAULT 120, concurrency_limit INTEGER NOT NULL DEFAULT 10,
  member_id VARCHAR(36) REFERENCES members(id),
  requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE IF NOT EXISTS gateway_instances (
  id VARCHAR(36) PRIMARY KEY, release_id VARCHAR(100) NOT NULL,
  lease_until BIGINT NOT NULL, draining BOOLEAN NOT NULL DEFAULT FALSE
);
ALTER TABLE requests ADD COLUMN IF NOT EXISTS owner_instance VARCHAR(36);
CREATE INDEX IF NOT EXISTS requests_owner_state ON requests(owner_instance,state);
CREATE TABLE IF NOT EXISTS coordination_locks (name VARCHAR(40) PRIMARY KEY);
INSERT INTO coordination_locks(name) SELECT 'admission' WHERE NOT EXISTS (SELECT 1 FROM coordination_locks WHERE name='admission');
INSERT INTO coordination_locks(name) SELECT 'authentication' WHERE NOT EXISTS (SELECT 1 FROM coordination_locks WHERE name='authentication');
CREATE TABLE IF NOT EXISTS gateway_admissions (
  id VARCHAR(36) PRIMARY KEY, member_id VARCHAR(36) NOT NULL, owner_instance VARCHAR(36) NOT NULL,
  admitted_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, closed BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS admission_member_time ON gateway_admissions(member_id,admitted_at);
CREATE TABLE IF NOT EXISTS auth_attempts (id VARCHAR(36) PRIMARY KEY, subject_hash VARCHAR(64) NOT NULL, attempted_at BIGINT NOT NULL);
CREATE INDEX IF NOT EXISTS auth_attempt_time ON auth_attempts(attempted_at);
CREATE INDEX IF NOT EXISTS requests_page ON requests(started_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS requests_member_page ON requests(member_id,started_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS requests_model_page ON requests(model,started_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS requests_upstream_page ON requests(upstream_request_id,started_at DESC);

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
ALTER TABLE requests ADD COLUMN IF NOT EXISTS client_session_id VARCHAR(100);
ALTER TABLE requests ADD COLUMN IF NOT EXISTS client_agent_id VARCHAR(100);
CREATE INDEX IF NOT EXISTS requests_client_session ON requests(client_session_id,started_at);
CREATE TABLE IF NOT EXISTS session_summaries (
  member_id VARCHAR(36) NOT NULL, session_id VARCHAR(100) NOT NULL,
  first_request_at TIMESTAMP WITH TIME ZONE NOT NULL, last_request_at TIMESTAMP WITH TIME ZONE NOT NULL,
  request_count INTEGER NOT NULL, summarized_through TIMESTAMP WITH TIME ZONE,
  status VARCHAR(24) NOT NULL DEFAULT 'pending', summary TEXT, category VARCHAR(30), model VARCHAR(100),
  cost_usd NUMERIC(14,6), attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP WITH TIME ZONE, last_error VARCHAR(200),
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (member_id,session_id)
);
CREATE INDEX IF NOT EXISTS session_summaries_recent ON session_summaries(last_request_at);
CREATE TABLE IF NOT EXISTS openai_api_settings (
  id SMALLINT PRIMARY KEY, weekly_cap_usd NUMERIC(10,2) NOT NULL, summaries_enabled BOOLEAN NOT NULL,
  summary_model VARCHAR(100) NOT NULL, input_usd_per_mtok NUMERIC(12,6) NOT NULL,
  cached_input_usd_per_mtok NUMERIC(12,6) NOT NULL, output_usd_per_mtok NUMERIC(12,6) NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_by VARCHAR(80) NOT NULL
);
INSERT INTO openai_api_settings(id,weekly_cap_usd,summaries_enabled,summary_model,input_usd_per_mtok,cached_input_usd_per_mtok,output_usd_per_mtok,updated_by)
SELECT 1,100.00,TRUE,'gpt-6-luna',0.10,0.01,0.50,'migration:V009' WHERE NOT EXISTS (SELECT 1 FROM openai_api_settings WHERE id=1);
CREATE TABLE IF NOT EXISTS openai_api_spend (
  id VARCHAR(36) PRIMARY KEY, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  purpose VARCHAR(30) NOT NULL, reference_id VARCHAR(200), model VARCHAR(100) NOT NULL,
  state VARCHAR(12) NOT NULL, reserved_usd NUMERIC(14,6) NOT NULL, cost_usd NUMERIC(14,6),
  input_tokens BIGINT, cached_input_tokens BIGINT, output_tokens BIGINT
);
CREATE INDEX IF NOT EXISTS openai_api_spend_time ON openai_api_spend(created_at);
INSERT INTO coordination_locks(name) SELECT 'openai_budget' WHERE NOT EXISTS (SELECT 1 FROM coordination_locks WHERE name='openai_budget');
CREATE TABLE IF NOT EXISTS broker_keys (
  id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL, purpose VARCHAR(30) NOT NULL, member_id VARCHAR(36),
  key_hash VARCHAR(64) NOT NULL UNIQUE, key_prefix VARCHAR(16) NOT NULL, key_sealed TEXT, active BOOLEAN NOT NULL DEFAULT TRUE,
  weekly_budget_usd NUMERIC(10,2), rpm INTEGER, allowed_models TEXT, max_output_tokens INTEGER NOT NULL DEFAULT 16384,
  expires_at TIMESTAMP WITH TIME ZONE, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(80) NOT NULL, revoked_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE IF NOT EXISTS broker_settings (
  id SMALLINT PRIMARY KEY, weekly_cap_usd NUMERIC(10,2) NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_by VARCHAR(80) NOT NULL
);
INSERT INTO broker_settings(id,weekly_cap_usd,updated_by) SELECT 1,100.00,'migration:V010' WHERE NOT EXISTS (SELECT 1 FROM broker_settings WHERE id=1);
CREATE TABLE IF NOT EXISTS broker_model_prices (
  model VARCHAR(100) PRIMARY KEY, input_usd_per_mtok NUMERIC(12,6) NOT NULL, cached_input_usd_per_mtok NUMERIC(12,6) NOT NULL,
  output_usd_per_mtok NUMERIC(12,6) NOT NULL, long_context_tokens INTEGER, long_input_usd_per_mtok NUMERIC(12,6),
  long_cached_input_usd_per_mtok NUMERIC(12,6), long_output_usd_per_mtok NUMERIC(12,6),
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_by VARCHAR(80) NOT NULL
);
INSERT INTO broker_model_prices(model,input_usd_per_mtok,cached_input_usd_per_mtok,output_usd_per_mtok,long_context_tokens,long_input_usd_per_mtok,long_cached_input_usd_per_mtok,long_output_usd_per_mtok,updated_by)
SELECT 'gpt-6-luna',0.10,0.01,0.50,272000,0.20,0.02,0.75,'migration:V010' WHERE NOT EXISTS (SELECT 1 FROM broker_model_prices WHERE model='gpt-6-luna');
INSERT INTO broker_model_prices(model,input_usd_per_mtok,cached_input_usd_per_mtok,output_usd_per_mtok,long_context_tokens,long_input_usd_per_mtok,long_cached_input_usd_per_mtok,long_output_usd_per_mtok,updated_by)
SELECT 'gpt-5.6-luna',0.20,0.02,1.20,272000,0.40,0.04,1.80,'migration:V010' WHERE NOT EXISTS (SELECT 1 FROM broker_model_prices WHERE model='gpt-5.6-luna');
INSERT INTO broker_model_prices(model,input_usd_per_mtok,cached_input_usd_per_mtok,output_usd_per_mtok,updated_by)
SELECT 'gpt-5-nano',0.05,0.005,0.40,'migration:V010' WHERE NOT EXISTS (SELECT 1 FROM broker_model_prices WHERE model='gpt-5-nano');
INSERT INTO broker_model_prices(model,input_usd_per_mtok,cached_input_usd_per_mtok,output_usd_per_mtok,updated_by)
SELECT 'gpt-5-mini',0.25,0.025,2.00,'migration:V010' WHERE NOT EXISTS (SELECT 1 FROM broker_model_prices WHERE model='gpt-5-mini');
CREATE TABLE IF NOT EXISTS broker_calls (
  id VARCHAR(36) PRIMARY KEY, created_at TIMESTAMP WITH TIME ZONE NOT NULL, key_id VARCHAR(36), purpose VARCHAR(30),
  member_id VARCHAR(36), model VARCHAR(100), streaming BOOLEAN NOT NULL DEFAULT FALSE, state VARCHAR(12) NOT NULL,
  status_code INTEGER, reserved_usd NUMERIC(14,6) NOT NULL DEFAULT 0, cost_usd NUMERIC(14,6),
  input_tokens BIGINT, cached_input_tokens BIGINT, output_tokens BIGINT, duration_ms BIGINT,
  upstream_request_id VARCHAR(200), error_type VARCHAR(60)
);
CREATE INDEX IF NOT EXISTS broker_calls_time ON broker_calls(created_at);
CREATE INDEX IF NOT EXISTS broker_calls_key_time ON broker_calls(key_id,created_at);
CREATE TABLE IF NOT EXISTS broker_admin_log (
  id VARCHAR(36) PRIMARY KEY, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  actor VARCHAR(80) NOT NULL, action VARCHAR(60) NOT NULL, target VARCHAR(100) NOT NULL, details_json TEXT NOT NULL
);
INSERT INTO coordination_locks(name) SELECT 'openai_broker' WHERE NOT EXISTS (SELECT 1 FROM coordination_locks WHERE name='openai_broker');
CREATE TABLE IF NOT EXISTS routing_settings (
  id SMALLINT PRIMARY KEY, gpt_models_enabled BOOLEAN NOT NULL, fallback_enabled BOOLEAN NOT NULL,
  codex_enabled BOOLEAN NOT NULL, broker_enabled BOOLEAN NOT NULL,
  map_opus VARCHAR(100) NOT NULL, map_sonnet VARCHAR(100) NOT NULL, map_haiku VARCHAR(100) NOT NULL,
  member_weekly_budget_usd NUMERIC(10,2) NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_by VARCHAR(80) NOT NULL
);
INSERT INTO routing_settings(id,gpt_models_enabled,fallback_enabled,codex_enabled,broker_enabled,map_opus,map_sonnet,map_haiku,member_weekly_budget_usd,updated_by)
SELECT 1,TRUE,FALSE,TRUE,TRUE,'gpt-6.2-sol','gpt-6.1-terra','gpt-6-luna',10.00,'migration:V011' WHERE NOT EXISTS (SELECT 1 FROM routing_settings WHERE id=1);
CREATE TABLE IF NOT EXISTS member_broker_keys (
  member_id VARCHAR(36) PRIMARY KEY, broker_key_id VARCHAR(36) NOT NULL, key_sealed TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE requests ADD COLUMN IF NOT EXISTS served_model VARCHAR(100);
INSERT INTO coordination_locks(name) SELECT 'member_broker_keys' WHERE NOT EXISTS (SELECT 1 FROM coordination_locks WHERE name='member_broker_keys');
ALTER TABLE routing_settings ADD COLUMN IF NOT EXISTS strategy VARCHAR(20) NOT NULL DEFAULT 'claude_first';
CREATE TABLE IF NOT EXISTS routing_deployments (
  id VARCHAR(40) PRIMARY KEY, name VARCHAR(100) NOT NULL, kind VARCHAR(20) NOT NULL, account VARCHAR(80) NOT NULL UNIQUE,
  priority INTEGER NOT NULL, weight INTEGER NOT NULL, enabled BOOLEAN NOT NULL, max_concurrency INTEGER,
  context_window_tokens INTEGER, credential_file VARCHAR(300),
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_by VARCHAR(80) NOT NULL
);
INSERT INTO routing_deployments(id,name,kind,account,priority,weight,enabled,updated_by)
SELECT 'claude-primary','Claude subscription','anthropic','primary',1,100,TRUE,'migration:V013' WHERE NOT EXISTS (SELECT 1 FROM routing_deployments WHERE id='claude-primary');
INSERT INTO routing_deployments(id,name,kind,account,priority,weight,enabled,updated_by)
SELECT 'codex-primary','Codex login','codex','codex',1,100,(SELECT codex_enabled FROM routing_settings WHERE id=1),'migration:V013' WHERE NOT EXISTS (SELECT 1 FROM routing_deployments WHERE id='codex-primary');
INSERT INTO routing_deployments(id,name,kind,account,priority,weight,enabled,updated_by)
SELECT 'openai-api-key','OpenAI API key (member keys via broker)','broker','broker',2,100,(SELECT broker_enabled FROM routing_settings WHERE id=1),'migration:V013' WHERE NOT EXISTS (SELECT 1 FROM routing_deployments WHERE id='openai-api-key');
ALTER TABLE routing_settings ADD COLUMN IF NOT EXISTS allowed_fails INTEGER NOT NULL DEFAULT 3;
ALTER TABLE routing_settings ADD COLUMN IF NOT EXISTS cooldown_seconds INTEGER NOT NULL DEFAULT 60;
ALTER TABLE routing_settings ADD COLUMN IF NOT EXISTS transient_retries INTEGER NOT NULL DEFAULT 2;
ALTER TABLE routing_settings ADD COLUMN IF NOT EXISTS first_byte_timeout_seconds INTEGER NOT NULL DEFAULT 90;

-- Request and review only. Approval does not create a broker key or enable inference.
CREATE TABLE IF NOT EXISTS virtual_key_requests (
  id VARCHAR(36) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL UNIQUE REFERENCES portal_accounts(id),
  state VARCHAR(32) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_at TIMESTAMP WITH TIME ZONE,
  CHECK ((state = 'pending' AND decided_at IS NULL) OR
         (state IN ('approved_awaiting_provisioning', 'rejected') AND decided_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS virtual_key_requests_review_queue ON virtual_key_requests(state, requested_at, id);
CREATE INDEX IF NOT EXISTS virtual_key_requests_decisions ON virtual_key_requests(state, decided_at, id);
