-- OpenAI key broker: virtual keys, prices, a global weekly cap and a call ledger.
-- The real provider key is never stored in the database.
CREATE TABLE IF NOT EXISTS broker_keys (
  id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL, purpose VARCHAR(30) NOT NULL, member_id VARCHAR(36),
  key_hash VARCHAR(64) NOT NULL UNIQUE, key_prefix VARCHAR(16) NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
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
