# Isolated VPN server prober

The `:prober` module is a separate Kotlin/JVM process. It does not share memory or a filesystem with the API and it does not change the public API contract. Its only data flow is:

1. page through `GET /v1/servers`;
2. select a bounded set, preferring `UNKNOWN`, then `DEGRADED`, `UNHEALTHY`, and non-recent `HEALTHY` entries;
3. rate-limit and fetch `GET /v1/servers/{id}/config`;
4. validate the profile as untrusted input;
5. optionally run a bounded OpenVPN child process;
6. submit the existing Phase 1 `ProbeResult` JSON to the internal endpoint.

## Result policy

Success is emitted only after OpenVPN reports `Initialization Sequence Completed`. Fixed failure mappings cover DNS resolution, transport connection, TLS negotiation/certificate verification, authentication, timeout, and generic OpenVPN exit. Profiles rejected by policy use `PROFILE_VALIDATION` plus a fixed reason code.

The following are infrastructure errors and cause no health report: catalog/config HTTP failure, `429`, API `5xx`, malformed API JSON, cancellation, process/workspace startup failure, and report submission failure. This prevents a broken worker or API outage from marking the catalog unhealthy.

The report uses `durationMs`, not latency. The API stores that as `lastProbeDurationMs` for either result. A successful `CONNECTED` report also updates `lastSuccessfulConnectionTimeMs`; a failed duration never overwrites it.

## Profile and process policy

The validator rejects configuration includes, plugins/providers, management interfaces, server/listener modes, proxies, script security overrides, all script hooks, daemon/log/status/PID/chroot/directory controls, password prompts, external certificate/key/tls-key paths, malformed inline blocks, NUL bytes, and oversized profiles. Every `remote` must match the current catalog summary's IP or hostname, and the normalized profile pins it to the catalog's public IP; local/private/link-local/carrier-grade-NAT/multicast targets are refused. Inline CA/certificate/key/tls material is accepted. Any profile-supplied `auth-user-pass` directive or inline credentials are removed.

The worker writes an accepted profile and a two-line `vpn`/`vpn` auth file into a unique owner-only directory. Both files are mode `0600` on POSIX filesystems. It starts `/usr/sbin/openvpn` through a direct `ProcessBuilder` argument list and appends worker-owned safety options after `--config`. It never invokes a shell and never logs captured OpenVPN text. The child receives a minimal environment.

After connection, failure, timeout, or cancellation, the worker first requests graceful termination, force-kills a resistant child, reaps it, closes streams, and recursively deletes the per-probe directory. The outer timeout is authoritative even if a profile contains multiple remotes.

## Scheduling and operations

Defaults are two concurrent probes, 35 seconds per probe, 1100 milliseconds between public/config request starts, 25 servers per sweep, a 15-minute sweep interval, and up to 30 seconds of startup/sweep jitter. Concurrency is hard-limited to four. A healthy server checked within one sweep interval is cooled down; unhealthy servers remain eligible. A process-local active-id set prevents two simultaneous probes for the same id.

Use `PROBER_DRY_RUN=true` with `PROBER_RUN_ONCE=true` for the first production validation. Dry-run still downloads and validates a bounded set, but it does not start OpenVPN and does not call the internal report endpoint. Normal run-once performs one real bounded sweep and exits.

The Compose profile is deliberately opt-in:

```bash
docker compose config
docker compose --profile prober config
PROBER_DRY_RUN=true PROBER_RUN_ONCE=true \
  docker compose --profile prober run --rm prober
```

Do not expose `/internal` at the reverse proxy, and do not add privileges, capabilities, host networking, host mounts, a Docker socket, or TUN access to the prober.
