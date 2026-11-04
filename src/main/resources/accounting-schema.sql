CREATE TABLE IF NOT EXISTS accounting_price_versions (
  id VARCHAR(100) PRIMARY KEY, provider VARCHAR(32) NOT NULL, model VARCHAR(200) NOT NULL,
  valid_from TIMESTAMP WITH TIME ZONE NOT NULL, valid_to TIMESTAMP WITH TIME ZONE,
  currency VARCHAR(3) NOT NULL, rates_json TEXT NOT NULL, source_url VARCHAR(500) NOT NULL,
  retrieved_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS accounting_price_lookup ON accounting_price_versions(provider,model,valid_from);
CREATE TABLE IF NOT EXISTS request_assessments (
  request_id VARCHAR(36) PRIMARY KEY, assessed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  usage_digest VARCHAR(64) NOT NULL, price_version VARCHAR(100), known_value_usd NUMERIC(24,10),
  cost_status VARCHAR(24) NOT NULL, missing_components TEXT NOT NULL,
  category VARCHAR(30) NOT NULL, method VARCHAR(30) NOT NULL, confidence VARCHAR(20) NOT NULL,
  classifier_version VARCHAR(40) NOT NULL, manual_category VARCHAR(30),
  source_usage TEXT, observed_components TEXT, source_complete BOOLEAN, source_capture_state VARCHAR(32),
  source_event_count INTEGER, source_detail_started_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE IF NOT EXISTS admin_action_log (
  id VARCHAR(36) PRIMARY KEY, actor_id VARCHAR(80) NOT NULL, action VARCHAR(80) NOT NULL,
  target_id VARCHAR(100) NOT NULL, details_json TEXT NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS admin_actions_time ON admin_action_log(occurred_at);
CREATE TABLE IF NOT EXISTS provider_quota_snapshots (
  id VARCHAR(64) PRIMARY KEY, provider VARCHAR(32) NOT NULL, upstream_account VARCHAR(80) NOT NULL,
  bucket VARCHAR(80) NOT NULL, used_percentage NUMERIC(10,5) NOT NULL,
  resets_at TIMESTAMP WITH TIME ZONE NOT NULL, observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
  source VARCHAR(40) NOT NULL, trusted BOOLEAN NOT NULL DEFAULT FALSE,
  CHECK(used_percentage >= 0 AND used_percentage <= 100)
);
CREATE INDEX IF NOT EXISTS quota_account_time ON provider_quota_snapshots(provider,upstream_account,observed_at);
CREATE TABLE IF NOT EXISTS subscription_invoices (
  id VARCHAR(36) PRIMARY KEY, provider VARCHAR(32) NOT NULL, upstream_account VARCHAR(80) NOT NULL,
  period_start TIMESTAMP WITH TIME ZONE NOT NULL, period_end TIMESTAMP WITH TIME ZONE NOT NULL,
  amount NUMERIC(20,4) NOT NULL, currency VARCHAR(3) NOT NULL,
  gateway_fraction NUMERIC(8,6) NOT NULL DEFAULT 0, allocation_basis VARCHAR(40) NOT NULL,
  provenance VARCHAR(40) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK(amount >= 0), CHECK(gateway_fraction >= 0 AND gateway_fraction <= 1), CHECK(period_end > period_start)
);
