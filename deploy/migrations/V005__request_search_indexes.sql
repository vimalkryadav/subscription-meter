-- Existing time/member/model/upstream indexes come from V002. Keep additional
-- indexes selective: status/client filters can use the bounded time scan.
CREATE INDEX IF NOT EXISTS requests_provider_account_page ON requests(provider,upstream_account,started_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS requests_key_page ON requests(member_key_prefix,started_at DESC,id DESC) WHERE member_key_prefix IS NOT NULL;
CREATE INDEX IF NOT EXISTS assessments_category_request ON request_assessments((COALESCE(manual_category,category,'unknown')),request_id);
CREATE INDEX IF NOT EXISTS quota_latest_trusted ON provider_quota_snapshots(provider,upstream_account,bucket,observed_at DESC,id DESC) WHERE trusted=TRUE;
