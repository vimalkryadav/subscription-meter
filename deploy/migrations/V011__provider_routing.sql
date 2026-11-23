-- Claude -> Codex -> API-key routing settings. Automatic fallback starts disabled.
CREATE TABLE IF NOT EXISTS routing_settings (
  id SMALLINT PRIMARY KEY, gpt_models_enabled BOOLEAN NOT NULL, fallback_enabled BOOLEAN NOT NULL,
  codex_enabled BOOLEAN NOT NULL, broker_enabled BOOLEAN NOT NULL,
  map_opus VARCHAR(100) NOT NULL, map_sonnet VARCHAR(100) NOT NULL, map_haiku VARCHAR(100) NOT NULL,
  member_weekly_budget_usd NUMERIC(10,2) NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_by VARCHAR(80) NOT NULL
);
INSERT INTO routing_settings(id,gpt_models_enabled,fallback_enabled,codex_enabled,broker_enabled,map_opus,map_sonnet,map_haiku,member_weekly_budget_usd,updated_by)
SELECT 1,TRUE,FALSE,TRUE,TRUE,'gpt-6.2-sol','gpt-6.1-terra','gpt-6-luna',10.00,'migration:V011' WHERE NOT EXISTS (SELECT 1 FROM routing_settings WHERE id=1);
-- Each member's broker virtual key for the last fallback tier (sealed; the gateway needs it to call the broker).
CREATE TABLE IF NOT EXISTS member_broker_keys (
  member_id VARCHAR(36) PRIMARY KEY, broker_key_id VARCHAR(36) NOT NULL, key_sealed TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE requests ADD COLUMN IF NOT EXISTS served_model VARCHAR(100);
INSERT INTO coordination_locks(name) SELECT 'member_broker_keys' WHERE NOT EXISTS (SELECT 1 FROM coordination_locks WHERE name='member_broker_keys');
