#!/usr/bin/env bash
# Sends the PR test result to the Telegram group. Everything comes in through the environment:
# TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID, TELEGRAM_MESSAGE_THREAD_ID (optional), and the
# LINT/UNIT/IT_OUTCOME, UNIT_*, IT_*, COV values, REPO, BRANCH, ACTOR, RUN_URL set by
# pr-check.yml.
set -u

esc() { printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'; }

# "tests failed skipped" -> a line with an emoji; outcome decides "not run"
line() { # label outcome "tests failed skipped"
  local label="$1" outcome="$2"
  read -r tests failed skipped <<<"$3"
  if [ "$outcome" = "skipped" ] || [ -z "${tests:-}" ]; then
    echo "⏭ <b>${label}:</b> not run"
  elif [ "$outcome" = "success" ] && [ "${failed:-0}" = "0" ]; then
    echo "✅ <b>${label}:</b> $((tests - skipped))/${tests} passed"
  else
    echo "❌ <b>${label}:</b> ${failed} failed of ${tests}"
  fi
}

if [ "$LINT_OUTCOME" = "success" ] && [ "$UNIT_OUTCOME" = "success" ] \
  && [ "$IT_OUTCOME" = "success" ]; then
  title="✅ <b>All Tests Passed</b>"
else
  title="❌ <b>Tests Failed</b>"
fi

text="${title}

Repository: $(esc "$REPO")
Branch: $(esc "$BRANCH")
Triggered by: $(esc "$ACTOR")
"
[ "$LINT_OUTCOME" != "success" ] && text+="
❌ <b>Lint (spotless):</b> failed"
text+="
$(line 'Unit Tests' "$UNIT_OUTCOME" "${UNIT_COUNTS:-}")
$(line 'Integration Tests' "$IT_OUTCOME" "${IT_COUNTS:-}")
"
if [ -n "${COV:-}" ]; then
  read -r lines statements functions branches <<<"$COV"
  text+="
📊 <b>Coverage (unit tests)</b>
Lines: ${lines}% | Statements: ${statements}% | Functions: ${functions}% | Branches: ${branches}%
"
fi
text+="
🔗 ${RUN_URL}"

curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
  --data-urlencode "chat_id=${TELEGRAM_CHAT_ID}" \
  ${TELEGRAM_MESSAGE_THREAD_ID:+--data-urlencode "message_thread_id=${TELEGRAM_MESSAGE_THREAD_ID}"} \
  --data-urlencode "parse_mode=HTML" \
  --data-urlencode "text=${text}" >/dev/null || echo "Telegram notification failed (ignored)"
