-- Database backstops for the two core invariants the service enforces today only through the
-- row-lock discipline (every ticket transition takes SELECT ... FOR UPDATE on the ticket, and a
-- bid accept re-reads the runner-ups under that lock). Audit finding F-07: a future code path or
-- a manual UPDATE that skips the lock silently breaks "one active ticket per user" and "one
-- ACCEPTED bid per ticket". These partial unique indexes move both invariants into Postgres.
--
-- The active-status list mirrors SosServiceImpl.ACTIVE_STATUSES. If a status joins or leaves that
-- list, the Java list and this index change together.
--
-- Both fail on violating data, so run the duplicate checks on SIT/prod first (the same pattern
-- V2's comment):
--   SELECT user_id, count(*) FROM sos_request
--    WHERE status IN ('PENDING','ACCEPTED','ON_THE_WAY','ARRIVED','IN_PROGRESS')
--    GROUP BY user_id HAVING count(*) > 1;
--   SELECT sos_id, count(*) FROM bid_sos WHERE status = 'ACCEPTED'
--    GROUP BY sos_id HAVING count(*) > 1;
--
-- Verified consistent with the current paths: applyRelease (mechanic release, no-show sweep) turns
-- the accepted bid CANCELLED before another can be accepted, and the accept domino re-reads the
-- PENDING bids under the ticket lock, so no status transition can produce a second ACCEPTED row
-- without a real bug -- which is exactly what these indexes exist to catch.
--
-- Plain CREATE (not CONCURRENTLY), same reasoning as V3: it runs inside Flyway's transaction and
-- at today's sizes blocks writes for milliseconds. CONCURRENTLY needs executeInTransaction=false
-- and migration of its own at millions-of-rows scales.
--
-- Rollback: DROP INDEX IF EXISTS for each (no data change, no schema shape change rows depend on).

CREATE UNIQUE INDEX IF NOT EXISTS uq_sos_one_active_per_user
    ON public.sos_request (user_id)
    WHERE status IN ('PENDING', 'ACCEPTED', 'ON_THE_WAY', 'ARRIVED', 'IN_PROGRESS');

CREATE UNIQUE INDEX IF NOT EXISTS uq_bid_one_accepted_per_sos
    ON public.bid_sos (sos_id)
    WHERE status = 'ACCEPTED';
