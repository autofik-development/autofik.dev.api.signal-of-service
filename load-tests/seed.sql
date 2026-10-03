-- Seed for load tests. Run after the app has started once (Hibernate creates the tables).
--   docker compose exec -T postgres psql -U sos -d sos -v users=10000 -v mechanics=3000 < seed.sql
-- Ids are md5-derived so reruns are idempotent and the CSV exports always match.
\if :{?users} \else \set users 10000 \endif
\if :{?mechanics} \else \set mechanics 3000 \endif

INSERT INTO users (user_id, name, phone, status)
SELECT md5('user-' || i)::uuid, 'Load User ' || i, '+855' || (10000000 + i), 'ACTIVE'
FROM generate_series(1, :users) i
ON CONFLICT DO NOTHING;

INSERT INTO vehicle (id, license_plate, car_brand, car_model, car_model_year, fuel_type, user_id)
SELECT md5('vehicle-' || i)::uuid, '2A-' || (1000 + i), 'Toyota', 'Camry', '2018', 'PETROL', md5('user-' || i)::uuid
FROM generate_series(1, :users) i
ON CONFLICT DO NOTHING;

-- Around Phnom Penh (11.56, 104.92), inside the app's accepted box (lat 10.41-14.69, lon 102.5-107.63).
INSERT INTO mechanic (id, full_name, phone_number, mechanic_role, kyc_status, verification_status,
                      current_location, base_location, service_radius_km, available_status)
SELECT md5('mechanic-' || i)::uuid, 'Load Mechanic ' || i, '+855' || (20000000 + i), 'FREELANCE',
       'APPROVED', 'APPROVED',
       ST_SetSRID(ST_MakePoint(104.92 + (random() - 0.5) * 0.3, 11.56 + (random() - 0.5) * 0.3), 4326)::geography,
       ST_SetSRID(ST_MakePoint(104.92, 11.56), 4326)::geography,
       15, (i % 10) < 7
FROM generate_series(1, :mechanics) i
ON CONFLICT DO NOTHING;

INSERT INTO mechanic_skill (id, mechanic_profile_id, skill_tags, vehicle_specialization, created_at)
SELECT md5('skill-' || i)::uuid, md5('mechanic-' || i)::uuid, ARRAY['TOW','BATTERY','TIRE'], 'ALL', now()
FROM generate_series(1, :mechanics) i
ON CONFLICT DO NOTHING;

ANALYZE;
