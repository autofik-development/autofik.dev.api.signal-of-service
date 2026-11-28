-- Billing's plan for each person, as of their last verify (plan_checked_at). A snapshot for places
-- that cannot call billing; the live verify decides. The token balance is deliberately not stored.
ALTER TABLE users
    ADD COLUMN billing_id       varchar(64),
    ADD COLUMN plan_id          varchar(64),
    ADD COLUMN plan_tier        varchar(32),
    ADD COLUMN plan_expired     boolean,
    ADD COLUMN plan_can_use     boolean,
    ADD COLUMN plan_checked_at  timestamp;

ALTER TABLE mechanic
    ADD COLUMN billing_id       varchar(64),
    ADD COLUMN plan_id          varchar(64),
    ADD COLUMN plan_tier        varchar(32),
    ADD COLUMN plan_expired     boolean,
    ADD COLUMN plan_can_use     boolean,
    ADD COLUMN plan_checked_at  timestamp;
