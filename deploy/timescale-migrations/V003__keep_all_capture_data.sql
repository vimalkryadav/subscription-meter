-- Captured data is never deleted (decided 2026-11-26). Remove every Timescale
-- retention policy; storage growth is handled by deduplication and compression.
-- V002 already dropped request_details chunks before 2026-11-19; those bodies
-- survive only in the 2026-11-16/17/18 backup archives.
SELECT remove_retention_policy('requests',if_exists=>TRUE);
SELECT remove_retention_policy('request_details',if_exists=>TRUE);
SELECT remove_retention_policy('request_events',if_exists=>TRUE);
SELECT remove_retention_policy('gateway_access',if_exists=>TRUE);
