-- A direct key is usable only through its owning, approved request.
ALTER TABLE virtual_key_requests ADD COLUMN IF NOT EXISTS broker_key_id VARCHAR(36) REFERENCES broker_keys(id);
CREATE UNIQUE INDEX IF NOT EXISTS virtual_key_requests_broker_key ON virtual_key_requests(broker_key_id);
