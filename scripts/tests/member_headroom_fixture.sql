BEGIN;
INSERT INTO members(id,name,key_hash,key_prefix,active,rpm,concurrency_limit) VALUES
('headroom-low','fixture','headroom-low','fixture',TRUE,60,4),
('headroom-high','fixture','headroom-high','fixture',TRUE,300,20),
('headroom-revoked','fixture','headroom-revoked','fixture',FALSE,60,4);
INSERT INTO portal_accounts(id,username,display_name,password_hash) VALUES
('headroom-account','headroom-fixture','fixture','not-a-login');
INSERT INTO portal_key_requests(id,account_id,state,reason,rpm,concurrency_limit,member_id)
VALUES('headroom-request','headroom-account','active','fixture',60,4,'headroom-low');
-- APPLY_HEADROOM_MIGRATION
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM members WHERE id='headroom-low' AND rpm=120 AND concurrency_limit=10 AND active)
    OR NOT EXISTS (SELECT 1 FROM members WHERE id='headroom-high' AND rpm=300 AND concurrency_limit=20 AND active)
    OR NOT EXISTS (SELECT 1 FROM members WHERE id='headroom-revoked' AND rpm=120 AND concurrency_limit=10 AND NOT active)
    OR NOT EXISTS (SELECT 1 FROM portal_key_requests WHERE id='headroom-request' AND rpm=120 AND concurrency_limit=10 AND state='active')
    OR (SELECT count(*) FROM admin_action_log WHERE target_id LIKE 'headroom-%')<>2
  THEN RAISE EXCEPTION 'Member headroom migration regression'; END IF;
END $$;
DELETE FROM portal_key_requests WHERE id='headroom-request';
INSERT INTO portal_key_requests(id,account_id,state,reason)
VALUES('headroom-default','headroom-account','pending','fixture');
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM portal_key_requests WHERE id='headroom-default' AND rpm=120 AND concurrency_limit=10)
  THEN RAISE EXCEPTION 'Member headroom defaults regression'; END IF;
END $$;
ROLLBACK;
