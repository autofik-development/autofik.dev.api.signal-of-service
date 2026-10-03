#!/bin/sh
# Pings every seeded mechanic once so current_h3_index is populated (the seed leaves it null).
cd "$(dirname "$0")"
BASE=${BASE:-http://localhost:18080}
cut -d, -f1-3 data/mechanics.csv | xargs -P 16 -n 1 sh -c '
  IFS=, read id lat lng <<EOT
$0
EOT
  curl -s -o /dev/null -w "%{http_code}\n" -X PATCH "'"$BASE"'/api/v1/mechanics/$id/location?lat=$lat&lng=$lng" \
    -H "mechanic-context: {\"mechanic\":{\"uuid\":\"$id\",\"otpVerify\":true,\"profileVerify\":true,\"tokenStatus\":\"active\",\"isLogout\":false}}"
' | sort | uniq -c
