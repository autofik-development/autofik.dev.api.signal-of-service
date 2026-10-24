-- Photos attached to every offer fan-out and every reconnect sync (SosMediaServiceImpl.photoUrls)
-- are read by ticket, and sos_media had no index on sos_id. Plain CREATE INDEX for the same reason
-- as V3.
CREATE INDEX IF NOT EXISTS idx_sos_media_sos
    ON public.sos_media (sos_id);
