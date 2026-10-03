#!/usr/bin/env bash
# One entry point for every automated test type. Each type writes its log to target/logs/<type>.log,
# its raw results to reports/data/ and its report to reports/report-*.md.
#
#   scripts/run-tests.sh unit            fast, no Docker
#   scripts/run-tests.sh integration     needs Docker (Testcontainers)
#   scripts/run-tests.sh fuzz            deeper fuzz pass; the STOMP frame fuzz test needs Docker
#   scripts/run-tests.sh load <scenario> [options]   scenario: smoke|spike|soak|stress|cold-start (needs the load stack)
#   scripts/run-tests.sh all             unit, integration, fuzz (not load)
#
# Exit code is non-zero if any selected type failed. All selected types run even if an earlier one fails.
set -u
cd "$(dirname "$0")/.."
mkdir -p target/logs reports/data

# Some Windows machines cannot open Java NIO selectors unless the unix-domain socket directory is short.
# Harmless elsewhere.
if [[ "${OS:-}" == "Windows_NT" ]]; then
  mkdir -p /c/tmp/uds
  export JDK_JAVA_OPTIONS="${JDK_JAVA_OPTIONS:-} -Djdk.net.unixdomain.tmpdir=C:/tmp/uds"
fi
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-test}"
MVN="./mvnw -B"
PY="${PYTHON:-python}"
command -v "$PY" >/dev/null 2>&1 || PY=python3
status=0

fresh() {  # Surefire and JaCoCo append to old output; a report must only see this run
  rm -rf target/surefire-reports target/jacoco.exec target/site/jacoco
}

keep_results() {  # keep_results <type>: Surefire wipes target/surefire-reports on the next run
  rm -rf "reports/data/surefire-$1"; mkdir -p "reports/data/surefire-$1"
  cp target/surefire-reports/TEST-*.xml "reports/data/surefire-$1/" 2>/dev/null || true
}

run_unit() {
  echo "== unit tests"
  fresh
  $MVN test -Dtest='!SosApplicationTests,!*FuzzTest,!*FuzzIntegrationTest' > target/logs/unit.log 2>&1; local rc=$?
  keep_results unit
  cp target/site/jacoco/jacoco.csv reports/data/jacoco-unit.csv 2>/dev/null || true
  local base=(); [[ -f reports/data/jacoco-baseline.csv ]] && base=(--baseline-jacoco reports/data/jacoco-baseline.csv)
  $PY scripts/test-report.py unit --surefire reports/data/surefire-unit --jacoco reports/data/jacoco-unit.csv "${base[@]}" || status=1
  [[ $rc -ne 0 ]] && status=1
}

run_integration() {
  echo "== integration tests (Docker)"
  fresh
  $MVN test -Pintegration -Dtest='*IntegrationTest,!*Fuzz*' > target/logs/integration.log 2>&1; local rc=$?
  keep_results integration
  $PY scripts/test-report.py integration --surefire reports/data/surefire-integration || status=1
  [[ $rc -ne 0 ]] && status=1
}

run_fuzz() {
  echo "== fuzz tests"
  fresh
  $MVN test -Pfuzz > target/logs/fuzz.log 2>&1; local rc=$?
  keep_results fuzz
  $PY scripts/test-report.py fuzz --surefire reports/data/surefire-fuzz || status=1
  [[ $rc -ne 0 ]] && status=1
}

run_load() {
  local scenario="${1:-smoke}"; shift || true
  echo "== load: $scenario"
  mkdir -p target/logs
  $PY load-tests/run-load.py "$scenario" "$@" 2>&1 | tee "target/logs/load-$scenario.log"
  [[ ${PIPESTATUS[0]} -ne 0 ]] && status=1
}

case "${1:-all}" in
  unit) run_unit ;;
  integration) run_integration ;;
  fuzz) run_fuzz ;;
  load) shift; run_load "$@" ;;
  all) run_unit; run_integration; run_fuzz ;;
  *) echo "usage: $0 unit|integration|fuzz|load <scenario>|all"; exit 64 ;;
esac
exit $status
