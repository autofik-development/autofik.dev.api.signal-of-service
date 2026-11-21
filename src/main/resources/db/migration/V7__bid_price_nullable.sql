-- Bids can be placed without a price while money features are not released
-- (sos.features.bid-price-enabled=false). Existing rows keep their price.
ALTER TABLE bid_sos ALTER COLUMN price DROP NOT NULL;
