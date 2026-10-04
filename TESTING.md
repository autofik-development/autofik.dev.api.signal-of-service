# Testing the SOS service

Three kinds of automated test, each with its own command, its own log and its own report under
`reports/`. Everything is non-interactive and returns a non-zero exit code on failure.

| Type | What it proves | Needs | Command | Report |
|---|---|---|---|---|
| Unit | business rules, validation, mapping, serializers, handler logic | JDK 21 | `scripts/run-tests.sh unit` | `reports/report-unit-tests.md` |
| Integration | the whole app against real PostgreSQL/PostGIS, Redis, Kafka | JDK 21, Docker | `scripts/run-tests.sh integration` | `reports/report-integration-tests.md` |
| Fuzz | malformed and random input to REST, STOMP, identity header, Kafka | JDK 21 (+ Docker for the STOMP frame fuzz) | `scripts/run-tests.sh fuzz` | `reports/report-fuzz-tests.md` |

`scripts/run-tests.sh all` runs all three. Load tests (JMeter) are kept outside the repo and run
locally only.

## Quick start

```bash
scripts/run-tests.sh unit                      # ~2 minutes, no Docker
scripts/run-tests.sh integration               # ~6 minutes, Docker running
scripts/run-tests.sh fuzz                      # ~5 minutes, Docker running

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
| Raw Surefire XML per type | `reports/data/surefire-<type>/` (not committed) |
| Reports | `reports/report-unit-tests.{md,json}`, `report-integration-tests.{md,json}`, `report-fuzz-tests.{md,json}` |
| Coverage (unit) | `reports/data/jacoco-unit.csv`, HTML in `target/site/jacoco/` |
| Hand-written commentary kept across regeneration | `reports/notes/<type>-tests.md` |

There is no scheduled CI job for this suite; run `scripts/run-tests.sh` locally.

## How reports are produced

Maven writes Surefire XML; `scripts/test-report.py <type>` reads it and
writes the Markdown and JSON. Test classes are filed by name (`*Fuzz*` is fuzz, package `integration`
is integration, the rest is unit). Tests print `[finding] ...` lines for things worth reporting
(a measured latency during an outage, say); these are lifted into the report's Findings section.
Coverage comes from JaCoCo (`jacoco-maven-plugin`, report only, no gate).

## Running against a specific environment

**Unit / fuzz (JVM-level):** nothing to point at; they do not touch an environment.

**Integration:** starts its own containers. Set `IT_DOCKER_NETWORK=<network>` when Maven itself runs
inside a container on a Docker network (see the comment in `IntegrationTestBase`).

## Assumptions

Decisions made where the brief left a gap; change them in one place each.

| Assumption | Where |
|---|---|
| A database is used (PostgreSQL/PostGIS); the brief's "no database" line was superseded by the follow-up. Integration tests run a real one; unit tests mock repositories. | `IntegrationTestBase` |
| Kafka is a single broker locally and a cluster when deployed. Tests run single-broker; replication-factor handling is covered by `KafkaTopologyCheck` unit tests only. | `KafkaConfig`, `KafkaTopologyCheck` |
| Integration tests mock Medusa, Google and OneSignal. S3 runs against `adobe/s3mock` in the media test only. | `IntegrationTestBase`, `SosMediaIntegrationTest` |

## What is deliberately not covered

* Real Kafka cluster behaviour (partition rebalances, broker loss in a 3-node cluster).
* Real Google, OneSignal, AWS S3 and Medusa calls.
* Gateway behaviour (signature or token checks live outside this service).
* Browser or mobile clients.

## Adding tests

Match the neighbours: JUnit 5 + Mockito + AssertJ, one behaviour per test, names that read as a
sentence. Integration tests extend `FlowTestSupport` (REST, STOMP and Kafka helpers) or
`IntegrationTestBase`; they are tagged `integration` and skipped by a normal build. Fuzz properties
use `Fuzz.SEED` so a PR build is deterministic. Print `[finding] ...` from a test to surface a
measurement or an observed behaviour in the report.
