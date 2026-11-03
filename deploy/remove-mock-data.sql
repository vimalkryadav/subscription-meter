-- Run with psql -v ON_ERROR_STOP=1 after a private pg_dump and stopping the app.
-- Explicit mock provenance and recognized, inactive acceptance fixtures only.
BEGIN;
CREATE TEMP TABLE retired_mock_requests ON COMMIT DROP AS
SELECT DISTINCT r.id FROM requests r LEFT JOIN request_details d ON d.request_id=r.id
JOIN members m ON m.id=r.member_id
WHERE d.upstream_mode='mock'
   OR (d.request_id IS NULL AND r.model='mock-model' AND m.active=FALSE
       AND m.name ~ '^smoke-40ff2146-[0-9]{2}$');

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM request_details d JOIN retired_mock_requests r ON r.id=d.request_id
             WHERE d.upstream_mode<>'mock') THEN
    RAISE EXCEPTION 'Refusing to delete a request with non-mock provenance';
  END IF;
END $$;

CREATE TEMP TABLE retained_requests ON COMMIT DROP AS
SELECT id FROM requests WHERE id NOT IN (SELECT id FROM retired_mock_requests);
CREATE TEMP TABLE retained_admins ON COMMIT DROP AS
SELECT id FROM portal_accounts WHERE role='admin';

CREATE TEMP TABLE retired_fixture_members ON COMMIT DROP AS
SELECT m.id FROM members m WHERE m.active=FALSE
AND (m.name ~ '^(smoke-[0-9a-f]{8}-[0-9]{2}|audit-smoke-[0-9a-f]{8}|tunnel-smoke-[0-9a-f]{8})$'
     OR m.name ~ '^Portal smoke test \(@portal-smoke-[0-9a-f]{10}\)$')
AND NOT EXISTS (SELECT 1 FROM requests r WHERE r.member_id=m.id AND r.id NOT IN (SELECT id FROM retired_mock_requests))
AND NOT EXISTS (SELECT 1 FROM request_details d WHERE d.member_id=m.id AND d.upstream_mode<>'mock');

CREATE TEMP TABLE retired_fixture_accounts ON COMMIT DROP AS
SELECT a.id FROM portal_accounts a WHERE a.role='member' AND a.display_name='Portal smoke test'
AND a.username IN ('portal-smoke-761179df8e','portal-smoke-c18bba8255','portal-smoke-e6c45d9757','portal-smoke-fa5968abb1')
AND NOT EXISTS (SELECT 1 FROM portal_key_requests k WHERE k.account_id=a.id AND k.member_id IS NOT NULL
                AND k.member_id NOT IN (SELECT id FROM retired_fixture_members));

SELECT 'mock_requests_to_remove' AS category,count(*) FROM retired_mock_requests
UNION ALL SELECT 'fixture_members_to_remove',count(*) FROM retired_fixture_members
UNION ALL SELECT 'fixture_accounts_to_remove',count(*) FROM retired_fixture_accounts;

DELETE FROM request_events WHERE request_id IN (SELECT id FROM retired_mock_requests);
DELETE FROM gateway_access WHERE request_id IN (SELECT id FROM retired_mock_requests)
   OR member_id IN (SELECT id FROM retired_fixture_members);
DELETE FROM request_details WHERE request_id IN (SELECT id FROM retired_mock_requests);
DELETE FROM requests WHERE id IN (SELECT id FROM retired_mock_requests);
DELETE FROM portal_sessions WHERE account_id IN (SELECT id FROM retired_fixture_accounts);
DELETE FROM portal_key_requests WHERE account_id IN (SELECT id FROM retired_fixture_accounts);
DELETE FROM portal_accounts WHERE id IN (SELECT id FROM retired_fixture_accounts);
-- Preserve any fixture key still referenced by a real account.
DELETE FROM members WHERE id IN (SELECT id FROM retired_fixture_members)
  AND NOT EXISTS (SELECT 1 FROM portal_key_requests k WHERE k.member_id=members.id);

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM retained_requests r WHERE NOT EXISTS (SELECT 1 FROM requests q WHERE q.id=r.id))
     OR EXISTS (SELECT 1 FROM retained_admins a WHERE NOT EXISTS (SELECT 1 FROM portal_accounts p WHERE p.id=a.id AND p.role='admin')) THEN
    RAISE EXCEPTION 'Preservation check failed';
  END IF;
  IF EXISTS (SELECT 1 FROM request_details WHERE upstream_mode='mock') THEN
    RAISE EXCEPTION 'Mock request details remain';
  END IF;
END $$;
COMMIT;
