-- SSO role last seen per user/mechanic, recorded by the profile syncs. Nullable: rows fill in on
-- their next request. No CHECK: SOS only records the role, it never decides on it.
ALTER TABLE users    ADD COLUMN role varchar(20);
ALTER TABLE mechanic ADD COLUMN role varchar(20);

-- Widen to match SSO's limits. Widening varchar in Postgres does not rewrite the table.
ALTER TABLE mechanic ALTER COLUMN full_name TYPE varchar(100);
ALTER TABLE mechanic ALTER COLUMN email     TYPE varchar(255);
