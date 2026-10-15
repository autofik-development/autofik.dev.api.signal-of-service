-- One bid row per mechanic and ticket. The service already guarantees this under the sos_request
-- row lock; this is the database backstop. Fails if duplicates exist, so check first:
--   SELECT sos_id, mechanic_id, count(*) FROM bid_sos GROUP BY 1, 2 HAVING count(*) > 1;
ALTER TABLE ONLY public.bid_sos
    ADD CONSTRAINT uk_bid_sos_sos_mechanic UNIQUE (sos_id, mechanic_id);
