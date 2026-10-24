-- Indexes for the queries that run all the time. Postgres indexes primary keys and UNIQUE
-- constraints only, and V1 added two more; every query below was reading its whole table.
--
-- Plain CREATE INDEX, not CONCURRENTLY: it runs inside Flyway's transaction with no extra Flyway
-- setting, and on tables this size it takes milliseconds. It blocks writes to the table while it
-- builds, so on a table with millions of rows use CONCURRENTLY in a migration of its own instead
-- (with executeInTransaction=false and spring.flyway.postgresql.transactional-lock=false).

-- Expiry sweep (every 30 s) and the startup re-arm: findByStatusAndBiddingExpiresAtBefore/After.
CREATE INDEX IF NOT EXISTS idx_sos_request_status_expiry
    ON public.sos_request (status, bidding_expires_at);

-- "Already have an active SOS?" on every create and rebroadcast, and the customer's own list.
CREATE INDEX IF NOT EXISTS idx_sos_request_user_status
    ON public.sos_request (user_id, status);

-- Timeline and the waiting-request lookups on every ticket read and transition.
CREATE INDEX IF NOT EXISTS idx_sos_status_history_sos_change
    ON public.sos_status_history (sos_id, change_at);

-- Auto-approve sweep (every 15 s): only the few rows still waiting for the customer.
CREATE INDEX IF NOT EXISTS idx_sos_status_history_pending
    ON public.sos_status_history (proposed_at)
    WHERE approved_status = 'PENDING_APPROVAL';

-- "Is this mechanic on a live job?" inside every dispatch, bid and accept, and the no-show sweep.
-- bid_sos (sos_id, ...) is already covered by uk_bid_sos_sos_mechanic.
CREATE INDEX IF NOT EXISTS idx_bid_sos_mechanic_status
    ON public.bid_sos (mechanic_id, status);

-- The skill join in every dispatch.
CREATE INDEX IF NOT EXISTS idx_mechanic_skill_mechanic
    ON public.mechanic_skill (mechanic_profile_id);
