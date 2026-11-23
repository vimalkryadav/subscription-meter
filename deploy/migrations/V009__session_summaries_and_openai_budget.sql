-- Claude Code session/agent attribution for each gateway request. Additive and nullable.
ALTER TABLE requests ADD COLUMN IF NOT EXISTS client_session_id VARCHAR(100);
ALTER TABLE requests ADD COLUMN IF NOT EXISTS client_agent_id VARCHAR(100);
CREATE INDEX IF NOT EXISTS requests_client_session ON requests(client_session_id,started_at) WHERE client_session_id IS NOT NULL;

-- Backfill retained history from recorded request headers. Regex extraction never fails on malformed JSON.
UPDATE requests r SET
  client_session_id=substring(d.request_headers from '"x-claude-code-session-id": ?\["([A-Za-z0-9._:-]{1,100})"'),
  client_agent_id=substring(d.request_headers from '"x-claude-code-agent-id": ?\["([A-Za-z0-9._:-]{1,100})"')
FROM request_details d
WHERE d.request_id=r.id AND d.started_at=r.started_at AND r.client_session_id IS NULL
  AND r.started_at>CURRENT_TIMESTAMP-INTERVAL '30 days';

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
