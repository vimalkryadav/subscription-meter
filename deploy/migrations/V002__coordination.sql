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
