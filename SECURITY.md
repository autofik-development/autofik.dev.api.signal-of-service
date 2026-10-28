# Security Policy

## Supported versions

This service is deployed continuously from `master`. Only the latest `master` (and the build running
on SIT/production) receives security fixes. There are no maintained release branches.

## Reporting a vulnerability

Please do **not** open a public issue, pull request or discussion for a suspected vulnerability.

1. Use GitHub private vulnerability reporting: open the repository's **Security** tab and choose
   **Report a vulnerability**
   (<https://github.com/autofik-development/autofik.dev.api.signal-of-service/security/advisories/new>).
2. Include what you found, the endpoint or WebSocket destination, the steps or request that show it,
   and what an attacker gains. A minimal proof of concept helps. Do not include real customer data,
   real tokens or secrets.
3. If you cannot use GitHub, tell the AutoFik backend team through your usual internal channel and
   ask for a private thread.

### What to expect

| Step | Target |
| --- | --- |
| Acknowledgement of your report | within 3 working days |
| First assessment (accepted, needs more information, or declined) | within 7 working days |
| Status updates while a fix is in progress | at least every 14 days |

- **Accepted:** we fix it on a private branch, release it, then credit you in the advisory unless you
  prefer not to be named. We ask you to keep the details private until the fix is deployed.
- **Declined:** we explain why (for example: intended behaviour, out of scope, not reproducible). You
  are welcome to disagree and send more detail.

### Scope

In scope: this service's REST API, STOMP/WebSocket endpoints, Kafka topics it produces or consumes,
database migrations, and its build and CI configuration.

Out of scope: the gateway, SSO, Medusa, the mobile apps and the hosting infrastructure (report those
to their owners), social engineering, and denial-of-service by volume.

## Security model, for reporters and reviewers

- **Identity comes from the internal gateway.** The gateway verifies the token and forwards
  `user-context` / `mechanic-context`; this service does not validate JWTs. The headers are
  unsigned, so the service must only be reachable from the gateway network. As a second layer, set
  `SOS_GATEWAY_SECRET`: every request except `/`, `/healthy` and `/actuator/**` must then carry it in
  `X-Edge-Auth` (the WebSocket upgrade included). See `INTEGRATION.md`.
- **Roles** are `USER` and `MECHANIC`; unknown roles are refused, not defaulted.
- **Data exposure is minimised by design:** mechanic discovery returns a summary without phone, email
  or exact position (positions snapped to about 1 km); only the SOS owner can read the location topic;
  SOS detail is limited to the owner, the assigned mechanic and eligible mechanics (parties' contact
  details only for the two people on the job).
- **Uploads** are limited to JPEG and PNG, checked by content, size and dimension limits, and capped
  per SOS.
- **Schema changes** go through Flyway only; no direct edits to SIT or production databases.
- **Logs** must not contain tokens, secrets, exact locations or personal data.
- **Metrics** (`/actuator/prometheus`) are exposed only where the environment lists them, on a private
  network. Counters use fixed labels, never ids or personal data.

## Handling secrets

Never commit `.env` files, API keys (Google Maps, OneSignal, S3) or the gateway secret. If you find
one in the repository or its history, report it as a vulnerability and rotate it.
