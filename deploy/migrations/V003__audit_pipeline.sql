CREATE TABLE IF NOT EXISTS audit_operations (
  operation_id VARCHAR(160) PRIMARY KEY,
  request_id VARCHAR(36) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
  part_count INTEGER NOT NULL,
  payload_bytes INTEGER NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  completed BOOLEAN NOT NULL DEFAULT FALSE,
  discard_reason VARCHAR(32),
  completed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS audit_operations_request ON audit_operations(request_id,kind);
CREATE INDEX IF NOT EXISTS audit_operations_pending ON audit_operations(completed,observed_at);
CREATE TABLE IF NOT EXISTS audit_payload_parts (
  operation_id VARCHAR(160) NOT NULL REFERENCES audit_operations(operation_id),
  part_number INTEGER NOT NULL,
  data TEXT NOT NULL,
  PRIMARY KEY(operation_id,part_number)
);
