-- Deployment registry for the routing engine. Priority 1 deployments (subscriptions) are always
-- preferred; priority 2 (the OpenAI API key via the broker) is used only when none of priority 1 can serve.
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
