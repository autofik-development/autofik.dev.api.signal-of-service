#!/bin/sh
# Clears run data (SOS tickets and everything hanging off them, Redis cooldowns) but keeps the seed.
cd "$(dirname "$0")"
docker compose exec -T postgres psql -U sos -d sos -c "TRUNCATE sos_request CASCADE"
docker compose exec -T redis redis-cli FLUSHALL
# Restart the app too: tickets carry in-memory 60 s expiry tasks, and ones left over from the last run
# hit the truncated table with ObjectOptimisticLockingFailureException (harmless, but noisy).
docker compose restart app
until curl -sf -o /dev/null http://localhost:18080/healthy; do sleep 1; done
