# Testing the SOS service

Four kinds of automated test, each with its own command, its own log and its own report under
`reports/`. Everything is non-interactive and returns a non-zero exit code on failure.

| Type | What it proves | Needs | Command | Report |
|---|---|---|---|---|
| Unit | business rules, validation, mapping, serializers, handler logic | JDK 21 | `scripts/run-tests.sh unit` | `reports/report-unit-tests.md` |
| Integration | the whole app against real PostgreSQL/PostGIS, Redis, Kafka | JDK 21, Docker | `scripts/run-tests.sh integration` | `reports/report-integration-tests.md` |
| Fuzz | malformed and random input to REST, STOMP, identity header, Kafka | JDK 21 (+ Docker for the STOMP frame fuzz) | `scripts/run-tests.sh fuzz` | `reports/report-fuzz-tests.md` |
| Load | latency, throughput, errors and limits under traffic | Docker, Python 3 | `scripts/run-tests.sh load <scenario>` | `reports/report-load-<scenario>.md` |

`scripts/run-tests.sh all` runs unit, integration and fuzz. Load tests are separate on purpose (they
need a running stack and a quiet machine).

## Quick start

```bash
scripts/run-tests.sh unit                      # ~2 minutes, no Docker
scripts/run-tests.sh integration               # ~6 minutes, Docker running
scripts/run-tests.sh fuzz                      # ~5 minutes, Docker running

# load: start the stack once, then run any scenario
docker build -f load-tests/Dockerfile.jmeter -t sos-jmeter load-tests
docker compose -f load-tests/docker-compose.yml up -d --build
docker compose -f load-tests/docker-compose.yml exec -T postgres psql -U sos -d sos -v users=10000 -v mechanics=3000 < load-tests/seed.sql
sh load-tests/warmup.sh                        # gives every seeded mechanic an H3 cell
scripts/run-tests.sh load smoke                # 2 minutes
scripts/run-tests.sh load spike                # 7 minutes
scripts/run-tests.sh load cold-start           # 6 minutes
scripts/run-tests.sh load stress               # 20 minutes (steps 50..1600 clients)
scripts/run-tests.sh load soak --duration 7200 # 2 hours; the default is 20 minutes
```

The plain Maven commands the script wraps, if you prefer them:

```bash
./mvnw test -Dtest='!SosApplicationTests,!*FuzzTest,!*FuzzIntegrationTest'   # unit (what the PR check runs, minus fuzz)
./mvnw test -Pintegration -Dtest='*IntegrationTest,!*Fuzz*'                  # integration
./mvnw test -Pfuzz                                                           # fuzz, deep
./mvnw test                                                                  # PR build: unit + shallow fuzz, no Docker
```

On Windows run the scripts from Git Bash. If `Selector.open()` fails with "Unable to establish loopback
connection" (seen on one dev laptop), the script already sets
`-Djdk.net.unixdomain.tmpdir=C:/tmp/uds`; set the same in `JDK_JAVA_OPTIONS` when calling Maven by hand.

## Where things are written

| Output | Location |
|---|---|
| Full Maven log per type | `target/logs/unit.log`, `integration.log`, `fuzz.log` |
| Load runner log | `target/logs/load-<scenario>.log` |
| Raw Surefire XML per type | `reports/data/surefire-<type>/` (not committed) |
| Reports | `reports/report-unit-tests.{md,json}`, `report-integration-tests.{md,json}`, `report-fuzz-tests.{md,json}`, `report-load-{smoke,spike,soak,stress,cold-start}.md` |
| Load time series (timestamp, concurrency, RPS, p50/p95/p99, error rate) | `reports/data/load-<scenario>.csv` |
| Load resource samples (CPU, heap, threads, pools, Redis, Kafka lag) | `reports/data/load-<scenario>-resources.csv` |
| Coverage (unit) | `reports/data/jacoco-unit.csv`, HTML in `target/site/jacoco/` |
| Hand-written commentary kept across regeneration | `reports/notes/<type>-tests.md`, `reports/notes/<scenario>.md` |
| Raw JMeter output | `load-tests/results/*.jtl`, `jmeter.log` (not committed) |

CI should collect `reports/**/*.md`, `reports/**/*.json`, `reports/data/**/*.csv` and `target/logs/*.log`.
`.github/workflows/test-suite.yml` does exactly that.

## How reports are produced

* **Unit, integration, fuzz:** Maven writes Surefire XML; `scripts/test-report.py <type>` reads it and
  writes the Markdown and JSON. Test classes are filed by name (`*Fuzz*` is fuzz, package `integration`
  is integration, the rest is unit). Tests print `[finding] ...` lines for things worth reporting
  (a measured latency during an outage, say); these are lifted into the report's Findings section.
  Coverage comes from JaCoCo (`jacoco-maven-plugin`, report only, no gate).
* **Load:** `load-tests/run-load.py` resets the stack, runs JMeter in a container, samples the app
  (Prometheus), Redis (`INFO`), Kafka (offsets and consumer-group lag) and the container every 5 to 15
  s, then computes percentiles from the JMeter results and writes the report and CSVs. Pass or fail is
  decided by the criteria listed in each report.
* A report states what it ran. If a run was shorter than the scenario calls for (the default soak is
  20 minutes, the target is 2 to 8 hours) the report says so in its Findings.

## Running against a specific environment

**Unit / fuzz (JVM-level):** nothing to point at; they do not touch an environment.

**Integration:** starts its own containers. Set `IT_DOCKER_NETWORK=<network>` when Maven itself runs
inside a container on a Docker network (see the comment in `IntegrationTestBase`).

**Load:** the defaults target the local compose stack. Override with environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `LOAD_BASE_URL` | `http://localhost:18080` | HTTP base URL as seen from the machine running the script (health polling) |
| `LOAD_HOST`, `LOAD_PORT` | `app`, `8080` | Host and port JMeter uses for HTTP **and** `ws://` (as seen from the JMeter container) |
| `LOAD_NETWORK` | `sos-loadtest_default` | Docker network JMeter joins; empty = default bridge |
| `LOAD_EXTERNAL` | unset | `1` = target is not the compose stack: no database reset, no app restart, no container stats |
| `PROM_URL` | `http://localhost:19090` | Prometheus scraping `/actuator/prometheus` |
| `KAFKA_CONTAINER`, `KAFKA_BOOTSTRAP_SERVERS` | compose Kafka, `localhost:9092` | Where offsets and consumer lag are read |
| `REDIS_CONTAINER` (or `REDIS_HOST`, `REDIS_PORT`) | compose Redis | Where memory, ops/s and command time are read |
| `APP_CONTAINER`, `DB_POOL_MAX`, `SOS_CREATE_COOLDOWN_SECONDS` | compose defaults | Recorded in the report / read by the stack |
| `SOAK_DURATION`, `STRESS_MIN_KNEE` | `1200`, `200` | Soak seconds, and the client count the stress knee must reach |

```bash
# example: an environment that is not the compose stack, reachable over the network
LOAD_EXTERNAL=1 LOAD_NETWORK= LOAD_HOST=sos.test.internal LOAD_PORT=8080 \
LOAD_BASE_URL=http://sos.test.internal:8080 PROM_URL=http://prometheus.test.internal:9090 \
scripts/run-tests.sh load smoke
```

Authentication: the service does no token validation. Identity arrives as the `user-context` /
`mechanic-context` JSON headers written by the gateway, and the JMeter plans send those directly, built
from the seeded user and mechanic ids. There are no tokens or credentials in the repo. Because those
headers are unsigned, **only point load tests at an environment reachable from the test network alone**.
Seeded data is synthetic (md5-derived ids, no personal data).

## Assumptions

Decisions made where the brief left a gap; change them in one place each.

| Assumption | Where |
|---|---|
| A database is used (PostgreSQL/PostGIS); the brief's "no database" line was superseded by the follow-up. Integration and load tests run a real one; unit tests mock repositories. | `IntegrationTestBase`, `load-tests/docker-compose.yml` |
| Kafka is a single broker locally and a cluster when deployed. Tests run single-broker; replication-factor handling is covered by `KafkaTopologyCheck` unit tests only. | `KafkaConfig`, `KafkaTopologyCheck` |
| Expected peak load is 200 concurrent clients; knee must be at or above it. Not a measured number. | `STRESS_MIN_KNEE` |
| Latency target for ping-to-broadcast: p95 under 100 ms. Spike: error rate under 5 % during the surge, back to 1.5 x baseline within 60 s. Soak: no more than 10 % drift in heap, threads, pool use, Redis memory. Cold start: ready within 120 s. | top of `run-load.py` |
| One load "client" is a customer plus a mechanic: it creates one SOS, opens two sockets, then loops ping, GET and PATCH with 0.5 to 2 s think time. Bids, status changes and reconnect storms are not in the load mix. | `plans/stress-stomp.jmx` |
| Google Maps runs with an empty API key in the load stack: the service still calls Google, which answers "Invalid API key" (logged as WARN, ETA and address come back null). That costs nothing but it is real egress and a real round trip; Medusa is a WireMock stub; OneSignal is called with a fake app id and answers 400 (also WARN). The real ETA path is not load-tested. | `load-tests/docker-compose.yml` |
| Load generator, app and dependencies share one machine in the local stack, so numbers are pessimistic. | each load report |
| Integration tests mock Medusa, Google and OneSignal; S3 is not exercised. | `IntegrationTestBase` |
| "Redis latency" is the server-side time per command from `INFO commandstats`, not a client round trip. | `run-load.py` |
| Kafka throughput is the growth of the `sos.*` and `mechanic.*` end offsets; lag is the sum over the app's consumer group. | `run-load.py` |

## What is deliberately not covered

* Real Kafka cluster behaviour (partition rebalances, broker loss in a 3-node cluster).
* Real Google, OneSignal, S3 and Medusa calls.
* Hours-long soak in CI; a scheduled or manual job on a quiet host is the place for it.
* Gateway behaviour (signature or token checks live outside this service).
* Browser or mobile clients.

## Adding tests

Match the neighbours: JUnit 5 + Mockito + AssertJ, one behaviour per test, names that read as a
sentence. Integration tests extend `FlowTestSupport` (REST, STOMP and Kafka helpers) or
`IntegrationTestBase`; they are tagged `integration` and skipped by a normal build. Fuzz properties
use `Fuzz.SEED` so a PR build is deterministic. Print `[finding] ...` from a test to surface a
measurement or an observed behaviour in the report.
