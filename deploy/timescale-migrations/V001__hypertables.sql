CREATE EXTENSION IF NOT EXISTS timescaledb;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='requests'::regclass AND contype='p'
             AND pg_get_constraintdef(oid)='PRIMARY KEY (id)') THEN
    ALTER TABLE requests DROP CONSTRAINT requests_pkey;
    ALTER TABLE requests ADD PRIMARY KEY(id,started_at);
  END IF;
END $$;
CREATE INDEX IF NOT EXISTS requests_id_lookup ON requests(id);
SELECT create_hypertable('requests',by_range('started_at',INTERVAL '1 day'),if_not_exists=>TRUE,migrate_data=>TRUE);
SELECT create_hypertable('request_details',by_range('started_at',INTERVAL '1 day'),if_not_exists=>TRUE,migrate_data=>TRUE);
SELECT create_hypertable('request_events',by_range('observed_at',INTERVAL '1 day'),if_not_exists=>TRUE,migrate_data=>TRUE);
SELECT create_hypertable('gateway_access',by_range('started_at',INTERVAL '1 day'),if_not_exists=>TRUE,migrate_data=>TRUE);
-- Preserve existing policies; policy changes require a new reviewed migration.
SELECT add_retention_policy('requests',INTERVAL '90 days',if_not_exists=>TRUE);
SELECT add_retention_policy('request_details',INTERVAL '30 days',if_not_exists=>TRUE);
SELECT add_retention_policy('request_events',INTERVAL '30 days',if_not_exists=>TRUE);
SELECT add_retention_policy('gateway_access',INTERVAL '30 days',if_not_exists=>TRUE);
