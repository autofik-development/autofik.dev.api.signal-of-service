# SOS service: endpoint list


"Customer" is a `user-context` caller, "mechanic" a `mechanic-context` caller (both set by the gateway). "Any" means either role; the service then decides visibility per resource (404 when you may not see it).

## REST

Base path `/api/v1`.

### SOS
| Method | Path | Who | Params / body | Success |
|---|---|---|---|---|
| POST | `/sos` | customer | `CreateSosRequest` | 201 |
| GET | `/sos` | any | `?status=&page=&size=&sort=` | 200 page |
| GET | `/sos/{sosId}` | any (owner, bidder, eligible mechanic) | | 200 |
| GET | `/sos/{sosId}/history` | any (owner, assigned mechanic) | | 200 |
| GET | `/sos/{sosId}/nearby-mechanics` | any (same as `GET /sos/{sosId}`) | | 200 |
| PATCH | `/sos/{sosId}/status` | mechanic (assigned) | `?toStatus=ON_THE_WAY\|IN_PROGRESS` | 200 |
| POST | `/sos/{sosId}/arrival` | mechanic (assigned) | | 201 |
| PATCH | `/sos/{sosId}/customer-no-show` | mechanic (assigned) | | 200 |
| PATCH | `/sos/{sosId}/mechanic-release` | mechanic (assigned) | | 200 |
| PATCH | `/sos/{sosId}/still-coming` | mechanic (assigned) | | 200 |
| POST | `/sos/{sosId}/completion` | mechanic (assigned) | `{mechanicId, toStatus: "COMPLETED"}` | 201 |
| PATCH | `/sos/completion` | customer | `{userId, historyId, approvedStatus: ACCEPTED\|REJECTED}` | 200 |
| PATCH | `/sos/{sosId}/cancel` | customer (owner) | | 200 |
| POST | `/sos/{sosId}/rebroadcast` | customer (owner) | | 200 |

### Media
| Method | Path | Who | Params / body | Success |
|---|---|---|---|---|
| POST | `/sos/{sosId}/media` | any (owner, assigned or eligible mechanic) | multipart field `file` | 201 |
| GET | `/sos/{sosId}/media` | any (same) | | 200 |

### Bids
| Method | Path | Who | Params / body | Success |
|---|---|---|---|---|
| POST | `/bids` | mechanic | `{mechanicId, sosId, price}` | 201 |
| GET | `/bids/sos/{sosId}` | any (owner sees all, a mechanic only their own) | `?sortBy=NEAREST\|CHEAPEST\|TOP_RATED` | 200 |
| GET | `/bids/mechanic/{mechanicId}` | mechanic (own id only) | `?page=&size=&sort=` | 200 page |
| PATCH | `/bids/{bidId}/status` | customer (SOS owner) | `{userId, status: ACCEPTED\|REJECTED}` | 200 |
| PATCH | `/bids/{bidId}/cancel` | mechanic (the bid's owner) | | 200 |

### Mechanics
| Method | Path | Who | Params / body | Success |
|---|---|---|---|---|
| GET | `/mechanics/{mechanicId}` | any | | 200 |
| GET | `/mechanics/available` | customer | `?lat=&lng=&problemType=&radiusKm=` (radius 5–20, default 15) | 200 |
| GET | `/mechanics/{mechanicId}/skills` | any | | 200 |
| PATCH | `/mechanics/{mechanicId}/location` | mechanic (own id only) | `?lat=&lng=&sosId=` (`sosId` optional) | 204 |
| GET | `/mechanics/{mechanicId}/location` | any (the mechanic, or the customer on an active job with them) | | 200 |
| PATCH | `/mechanics/{mechanicId}/availability` | mechanic (own id only) | `?available=true\|false` | 200 |

### Health (no `/api/v1` prefix, no auth on the service itself; not routed by the testing gateway `api.testing.autofik.com`, so reach them on the service directly)
| Method | Path | Success |
|---|---|---|
| GET | `/` | 200 `{service, status, timestamp}` |
| GET | `/healthy`, `/health` | 200 UP or DEGRADED, 503 when the database is down |
| GET | `/actuator/health` | 200 |

## WebSocket (STOMP 1.2, raw WebSocket, not SockJS)

Connect to `/ws`. First frame `CONNECT`, then subscribe and send. Clients may SEND only to `/app/**`.

### Send (client → server)
| Destination | Who | Payload | Reply |
|---|---|---|---|
| `/app/bids` | mechanic | `{sosId, price, clientMessageId?}` | `BID_PLACED` on `/user/queue/bid-results` |
| `/app/bids/decision` | customer (SOS owner) | `{bidId, status: ACCEPTED\|REJECTED, clientMessageId?}` | `BID_DECIDED` on `/user/queue/bid-results` |
| `/app/bids/cancel` | mechanic (the bid's owner) | `{bidId, clientMessageId?}` | `BID_WITHDRAWN` on `/user/queue/bid-results` |
| `/app/bids/sync` | any participant | `{sosId, clientMessageId?}` | `BIDS_SNAPSHOT` on `/user/queue/bid-results` |
| `/app/sos/open` | mechanic | `{clientMessageId?}` | `SOS_CREATED` frames on `/user/queue/sos-new-request`, then `OPEN_SOS_SYNCED` on `/user/queue/bid-results` |
| `/app/mechanic/location` | mechanic | `{latitude, longitude, sosId?}` | none |

Any rejected command: `{error, status, clientMessageId}` on `/user/queue/errors`.

`clientMessageId`: optional string (at most 64 characters) chosen by the app, copied unchanged into the reply or error so the app can match it to the request. `null` when omitted. Not an idempotency key: the server does not deduplicate by it.

`/app/mechanic/location`: with `sosId` every ping is broadcast on `/topic/sos/{sosId}/location`; without it, at most one ping per 10 s is (server setting `MECHANIC_LOCATION_PG_SYNC_SECONDS`, default 10). Broadcast only for the mechanic doing the job: their bid on that SOS is `ACCEPTED` and the SOS is `ACCEPTED`, `ON_THE_WAY`, `ARRIVED` or `IN_PROGRESS`. Pings from any other mechanic, or a `sosId` they are not assigned to, are saved but not broadcast.

### Subscribe (server → client)
| Destination | Who receives |
|---|---|
| `/user/queue/sos-new-request` | mechanic: new offers |
| `/user/queue/sos-updates` | customer and mechanic: bid, status and request events |
| `/user/queue/bid-results` | the sender of an `/app/bids…` or `/app/sos/open` command |
| `/user/queue/errors` | the sender of a rejected command |
| `/topic/sos/{sosId}/location` | participants of that SOS: the assigned mechanic's live position |
| `/topic/sos/{sosId}/media` | participants of that SOS: new photo/video uploaded |

Subscribing to a `/topic/sos/{sosId}/…` you are not part of returns an ERROR frame and closes the connection.
