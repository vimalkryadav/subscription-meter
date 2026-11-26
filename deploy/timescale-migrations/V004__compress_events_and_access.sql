-- Lossless Timescale compression for append-only history older than 7 days.
-- Nothing is deleted: compressed chunks stay queryable and accept inserts.
-- request_details is left uncompressed because CaptureCompactor updates it.
ALTER TABLE request_events SET (timescaledb.compress,
  timescaledb.compress_segmentby = 'request_id',
  timescaledb.compress_orderby = 'sequence, observed_at');
SELECT add_compression_policy('request_events', INTERVAL '7 days', if_not_exists=>TRUE);
ALTER TABLE gateway_access SET (timescaledb.compress,
  timescaledb.compress_orderby = 'started_at DESC, request_id');
SELECT add_compression_policy('gateway_access', INTERVAL '7 days', if_not_exists=>TRUE);
