-- Profile and vehicle photos, synced from the SSO profile in the gateway context headers.
ALTER TABLE public.users    ADD COLUMN IF NOT EXISTS profile_image VARCHAR(500);
ALTER TABLE public.mechanic ADD COLUMN IF NOT EXISTS profile_image VARCHAR(500);
ALTER TABLE public.vehicle  ADD COLUMN IF NOT EXISTS image         VARCHAR(500);
