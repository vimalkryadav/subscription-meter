-- One-time operator-requested increase. Preserve higher custom limits and revocations.
ALTER TABLE portal_key_requests ALTER COLUMN rpm SET DEFAULT 120;
ALTER TABLE portal_key_requests ALTER COLUMN concurrency_limit SET DEFAULT 10;

INSERT INTO admin_action_log(id,actor_id,action,target_id,details_json)
SELECT gen_random_uuid()::text, 'migration:V007', 'member.limits.update', id,
       json_build_object('previousRpm',rpm,'previousConcurrency',concurrency_limit,
                         'rpm',GREATEST(rpm,120),'concurrency',GREATEST(concurrency_limit,10),
                         'reason','Operator-requested member admission increase')::text
FROM members WHERE rpm<120 OR concurrency_limit<10;

UPDATE members SET rpm=GREATEST(rpm,120), concurrency_limit=GREATEST(concurrency_limit,10)
WHERE rpm<120 OR concurrency_limit<10;

-- Claims and rotations use the approved request values; keep the increase there too.
UPDATE portal_key_requests SET rpm=GREATEST(rpm,120), concurrency_limit=GREATEST(concurrency_limit,10)
WHERE state IN ('pending','approved','active') AND (rpm<120 OR concurrency_limit<10);
