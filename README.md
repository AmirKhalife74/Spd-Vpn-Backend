# SPD VPN Backend

Small Ktor service that downloads VPN Gate's public CSV catalog outside the filtered network, validates it, keeps a last-known-good disk cache, and exposes a compact JSON API to SPD VPN clients.

This first phase deliberately has no database, user accounts, premium servers, or fake fallback data.

## What it does

- Fetches the VPN Gate CSV immediately at startup and every 5 minutes by default.
- Retries a failed refresh after at most 30 seconds while keeping the good cache online.
- Parses RFC 4180 CSV, including quoted commas and multiline fields.
- Decodes each embedded OpenVPN profile to detect protocol and remote port.
- Enriches each CSV refresh from the VPN Gate HTML table with the actual native SoftEther TCP listener when published.
- Rejects malformed upstream responses instead of replacing good data.
- Persists an atomic last-known-good JSON snapshot on disk.
- Serves stale cached data when VPN Gate is temporarily unavailable.
- Supports country/protocol/search filters, sorting, and pagination.
- Keeps full OpenVPN profiles out of list responses; clients request one profile by server id.
- Adds gzip, ETag/304, basic per-IP rate limiting, CORS configuration, and health endpoints.
- Tracks per-server probe health in a separate atomic disk snapshot.
- Provides an opt-in, isolated JVM worker that validates untrusted profiles and checks only whether OpenVPN reaches `CONNECTED`.

## API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health/live` | Process liveness for Docker/Caddy |
| `GET` | `/v1/health` | Catalog readiness and freshness |
| `GET` | `/v1/countries` | Countries and available-server counts |
| `GET` | `/v1/servers` | Filtered, sorted, paginated server summaries |
| `GET` | `/v1/servers/{id}/config` | Complete decoded OpenVPN profile |
| `POST` | `/internal/v1/probe-results` | Authenticated result intake for the isolated probe worker |

`GET /v1/servers` query parameters:

| Parameter | Values | Default |
|---|---|---|
| `country` | Two-letter code such as `JP` | all |
| `protocol` | `UDP`, `TCP`, `UNKNOWN` | all |
| `health` | `healthy`, `degraded`, `unhealthy`, `unknown` | all |
| `minScore` | Non-negative integer | none |
| `maxPingMs` | Non-negative integer | none |
| `q` | Host, IP, country, or operator search | none |
| `sort` | `score`, `speed`, `ping`, `uptime`, `users`, `sessions` | `score` |
| `order` | `asc`, `desc` | `desc` |
| `offset` | Non-negative integer | `0` |
| `limit` | `1...200` | `50` |

Example:

```bash
curl 'http://localhost:8080/v1/servers?country=JP&protocol=UDP&sort=ping&order=asc&limit=20'
```

The complete machine-readable contract is in [`docs/openapi.yaml`](docs/openapi.yaml).

## Run locally

Requirements: JDK 17. The checked-in Gradle Wrapper downloads the pinned Gradle version and verifies its SHA-256 checksum.

```bash
./gradlew test :prober:test
./gradlew run
```

Or run the complete production-style container:

```bash
docker compose up --build -d
curl http://127.0.0.1:8080/v1/health
```

The API is intentionally bound to `127.0.0.1` in `compose.yaml`. Put Caddy or Nginx in front of it for public HTTPS. `Caddyfile.example` explicitly returns `404` for `/internal` before proxying public routes.

For a short Persian VPS walkthrough, see [`docs/deployment-fa.md`](docs/deployment-fa.md).

## Configuration

Copy `.env.example` to `.env` for Docker Compose or provide these environment variables directly:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | HTTP listen port |
| `VPN_GATE_SOURCE_URL` | official iPhone CSV endpoint | Upstream catalog URL |
| `VPN_GATE_HTML_URL` | `https://www.vpngate.net/en/` | Best-effort SoftEther listener metadata page |
| `VPN_GATE_REFRESH_INTERVAL_SECONDS` | `300` | Refresh interval |
| `VPN_GATE_FETCH_TIMEOUT_SECONDS` | `30` | Upstream request timeout |
| `VPN_GATE_CACHE_PATH` | `data/vpngate-cache.json` | Last-known-good cache file |
| `HEALTH_SNAPSHOT_PATH` | `data/server-health.json` | Independent server-health snapshot |
| `VPN_GATE_MAX_STALE_SECONDS` | `86400` | Age after which health becomes degraded |
| `RATE_LIMIT_REQUESTS` | `120` | Token bucket capacity per client key |
| `RATE_LIMIT_WINDOW_SECONDS` | `60` | Token refill period |
| `CORS_ALLOWED_HOSTS` | `*` | Comma-separated web origins/hosts |
| `PROBER_SHARED_SECRET` | blank | Shared token for the internal probe endpoint; blank disables it |
| `PROBER_API_BASE_URL` | `http://api:8080` | Internal API origin used by the worker |
| `PROBER_SWEEP_INTERVAL_SECONDS` | `900` | Base delay between completed sweeps |
| `PROBER_INITIAL_JITTER_SECONDS` | `30` | Maximum startup and bounded per-sweep jitter |
| `PROBER_CONCURRENCY` | `2` | Concurrent probes; hard range `1...4` |
| `PROBER_PROBE_TIMEOUT_SECONDS` | `35` | Total per-process timeout; range `1...600` |
| `PROBER_CONFIG_REQUEST_INTERVAL_MS` | `1100` | Minimum spacing between public/config API requests; cannot be lower than `1100` |
| `PROBER_MAX_SERVERS_PER_SWEEP` | `25` | Bounded number selected in one sweep; maximum `200` |
| `PROBER_RUN_ONCE` | `false` | Run one bounded sweep and exit |
| `PROBER_DRY_RUN` | `false` | Fetch/select/validate only; never spawn OpenVPN or submit reports |

The rate-limit key checks `CF-Connecting-IP`, then `X-Forwarded-For`, then the direct remote address. Strip untrusted forwarding headers at the edge unless traffic is guaranteed to pass through Cloudflare or your own reverse proxy.

## Isolated server-health probing

The API does not install or run OpenVPN. The separate `:prober` Gradle application fetches the public server list, downloads one profile at a controlled rate, validates it as untrusted input, runs OpenVPN with a null device and no route/interface/script execution, and submits bounded JSON results to `POST /internal/v1/probe-results` with the `X-SpdVpn-Prober-Token` header. The endpoint returns `404` while `PROBER_SHARED_SECRET` is blank and rejects missing or invalid tokens with `401`.

The worker treats catalog/config download errors, malformed API responses, API `429`/`5xx`, process-start failures, report failures, and cancellation as worker/infrastructure errors. Those paths do not submit a server failure. Only validated profiles that actually run can produce DNS, transport, TLS, authentication, timeout, or OpenVPN-exit failures. Profile-policy rejection is reported with fixed uppercase codes; raw OpenVPN output and profile material are never sent to the API or logs.

The Compose service is opt-in. First validate real catalog profiles without opening VPN connections:

```bash
PROBER_DRY_RUN=true PROBER_RUN_ONCE=true \
  docker compose --profile prober run --rm prober
```

To enable scheduled probes, put the same long random `PROBER_SHARED_SECRET` in `.env` for both services, leave `PROBER_DRY_RUN=false`, then run:

```bash
docker compose --profile prober up --build -d
docker compose --profile prober logs -f --tail=100 prober
```

This starts real outbound OpenVPN handshakes. The prober image is deliberately separate and runs non-root with all Linux capabilities dropped, `no-new-privileges`, a read-only root filesystem, a bounded `/tmp`, and no ports, Docker socket, host networking, privileged mode, or TUN device. See [`docs/prober.md`](docs/prober.md) before enabling it.

Public server summaries add optional health fields. `lastProbeDurationMs` is the total duration of the latest probe, including a failed probe, while `lastSuccessfulConnectionTimeMs` is updated only by a successful report at the `CONNECTED` stage. Failed durations are never presented as connection latency. Servers without a report are `UNKNOWN`; one or two consecutive failures are `DEGRADED`, three failures are `UNHEALTHY`, and the next successful report restores `HEALTHY`. Public listings keep all servers and rank them `HEALTHY`, `UNKNOWN`, `DEGRADED`, then `UNHEALTHY` before applying the requested secondary sort.

Health persistence is independent from the VPN Gate catalog. Entries that remain absent from the current catalog for 24 hours are pruned. The existing `spdvpn-data` volume stores both snapshots.

## Deployment notes

1. Create an Ubuntu 24.04 VPS outside Iran.
2. Install Docker Engine and the Compose plugin.
3. Point `api.spdvpn.app` to the VPS.
4. Copy the project and run `docker compose up --build -d`; this starts only the API.
5. Install Caddy and adapt `Caddyfile.example`; Caddy obtains TLS certificates automatically.
6. Keep port `8080` private and expose only `80/443`.
7. Back up the named Docker volume, although the cache can always be rebuilt from upstream.

## Important behavior

- `source.stale=true` means the API is serving an older last-known-good snapshot.
- `/v1/health` returns `503` only when no usable snapshot exists at all.
- A refresh that downloads invalid CSV is recorded as a failure and never replaces the current snapshot.
- Protocol and port come from the actual OpenVPN profile embedded by VPN Gate. The backend does not invent a TCP variant when upstream only supplies UDP.
- `softEtherTcpPort` comes only from the HTML table's SSL-VPN cell. It is omitted when unknown; the backend never assumes port 443.
- OpenVPN exists only in the opt-in prober image. The API image and API container privileges remain unchanged.
