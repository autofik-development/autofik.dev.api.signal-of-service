#!/usr/bin/env bash
# Prints the Telegram message for a passing and a failing run, without sending anything:
#   bash .github/ci/preview-telegram.sh
# (curl is replaced by a function that prints the text it would have posted.)
set -u
cd "$(dirname "$0")"

curl() {
  local a
  for a in "$@"; do
    case "$a" in text=*) printf '%s\n' "${a#text=}" >&2 ;; esac
  done
}
export -f curl

export TELEGRAM_BOT_TOKEN=preview TELEGRAM_CHAT_ID=0
export REPO=autofik-development/autofik.dev.api.signal-of-service
export BRANCH=186-sit-code-backlog ACTOR=backendevautofik
export RUN_URL=https://github.com/autofik-development/autofik.dev.api.signal-of-service/actions/runs/123
export COV="94.95 94.30 94.85 84.18"
export LINT_OUTCOME=success UNIT_OUTCOME=success UNIT_COUNTS="2314 0 0"

echo "================ ALL PASSED ================"
IT_OUTCOME=success IT_COUNTS="229 0 0" \
  bash ./telegram-test-report.sh

echo
echo "================ INTEGRATION FAILED ================"
IT_OUTCOME=failure IT_COUNTS="229 2 0" \
  bash ./telegram-test-report.sh

echo
echo "================ LINT FAILED ================"
LINT_OUTCOME=failure IT_OUTCOME=success IT_COUNTS="229 0 0" \
  bash ./telegram-test-report.sh
