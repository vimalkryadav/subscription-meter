-- request_details holds the full request body of every gateway call. Claude Code
-- resends the whole conversation on each turn, so 30 days outgrew the host disk
-- (about 2.5 GB/day in November 2026). Keep bodies for 7 days; request metadata
-- keeps its 90-day policy, and events/access keep 30 days.
SELECT remove_retention_policy('request_details',if_exists=>TRUE);
SELECT add_retention_policy('request_details',INTERVAL '7 days');
-- Reclaim the space now instead of waiting for the daily policy run.
SELECT count(*) AS dropped_request_detail_chunks FROM drop_chunks('request_details',older_than=>INTERVAL '7 days');
