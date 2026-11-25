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
