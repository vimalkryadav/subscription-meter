-- Run with psql -v owner_username=USERNAME -v ON_ERROR_STOP=1.
-- Preserve every account and its password; replace only admin privileges.
BEGIN;
LOCK TABLE portal_accounts IN SHARE ROW EXCLUSIVE MODE;
SELECT count(*) = 1 AS owner_exists
FROM portal_accounts WHERE username = :'owner_username' \gset
\if :owner_exists
    DELETE FROM portal_sessions WHERE account_id IN (
        SELECT id FROM portal_accounts WHERE role='admin' AND username <> :'owner_username'
    );
    UPDATE portal_accounts SET role='member' WHERE role='admin';
    UPDATE portal_accounts SET role='admin' WHERE username=:'owner_username';
    CREATE UNIQUE INDEX IF NOT EXISTS portal_single_admin ON portal_accounts(role) WHERE role='admin';
    SELECT username,role FROM portal_accounts WHERE role='admin';
    COMMIT;
\else
    ROLLBACK;
    \echo 'Account not found; privileges unchanged.'
    \quit 1
\endif
