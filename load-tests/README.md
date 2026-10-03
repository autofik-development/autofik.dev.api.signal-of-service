# SOS load tests

Everything runs in Docker (the app cannot open NIO selectors natively on the dev laptop, see the vault note).

```
docker compose up -d --build          # app on localhost:18080
docker compose exec -T postgres psql -U sos -d sos -v users=10000 -v mechanics=3000 < seed.sql
# data/*.csv are generated from the seed (see the plan note); regenerate if the seed size changes
./reset.sh                            # between runs: clears tickets and Redis cooldowns, keeps seed

W="$(pwd -W)"   # Git Bash on Windows; use $(pwd) on Linux
MSYS_NO_PATHCONV=1 docker run --rm --network sos-loadtest_default -v "$W:/work" -v "$W/data:/data" -w /work \
  justb4/jmeter:5.5 -n -t plans/smoke.jmx -l results/smoke.jtl -Jhost=app -Jport=8080 -Jduration=60 -Jthreads=5
```

Delete `results/smoke.jtl` first, JMeter appends to an existing file.
Google Maps is unconfigured on purpose (no cost, ETA and address come back null); Medusa is a WireMock stub.
