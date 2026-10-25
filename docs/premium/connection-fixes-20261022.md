# Premium catalog identity diagnosis — 2026-10-22

This is a local source fix, **not a deployment**. No SSH, PasarGuard administration, direct
production database operations, account provisioning, commit or push occurred.

## Evidence

Normal authorized test-user APIs against the existing production backend returned a catalog
of 19 profiles. Repeated reads retained all display names/protocols/transports but changed
9–10 profile IDs. Profile requests for the first two selected catalog IDs returned HTTP 404;
another explicitly selected profile succeeded. Private profile contents, identifiers, tokens
and credentials were never printed or persisted by the audit tooling.

The old ID hash included endpoint host/port. Subscription endpoints rotate between catalog
and profile requests. Local `HttpPasarGuard` now uses a nonblank sanitized display name plus
protocol and transport as the logical identity (`logical-v2`). Profile GET still fetches current
private configuration, checks normal authorization and resolves only an unambiguous result.
Unnamed profiles use endpoint identity; duplicate logical aliases fail closed. No profile
cache, hidden fallback, account credential substitution or access-control change was added.

Deployment will change IDs once; Android must refresh the catalog before selection. Deploy
separately with the project's normal rollback procedures. This task did not deploy the fix.
Without a stable upstream logical ID, unnamed rotating entries cannot be safely reconciled.

## Existing normalization and Android compatibility

The user's existing `normalizeSpdTunProfile` remains intact: SOCKS/HTTP local inputs become
one TUN input and routing inboundTag references are rewritten. Tests verify preservation of
DNS, outbound transport/REALITY and secure configuration, plus rejection of allowInsecure.

Android's exact libXray API 3/core 26.10.10 additionally requires a flat string `env` map for
`xray.tun.fd`; a missing TUN name triggers forbidden Android interface enumeration. Those
platform adaptations were fixed in Android, not by inserting a guessed FD into backend JSON.
One actual selected production profile then passed core, device HTTPS, changed egress, raw UDP
DNS, background, notification disconnect, reconnect and logout tests on Android 35 emulator.
This does not validate every provider profile or physical device.

## Files and verification

- Modified: `src/main/kotlin/app/spdvpn/backend/premium/PasarGuard.kt` (logical ID only).
- Added: `src/test/kotlin/app/spdvpn/backend/premium/PasarGuardTunNormalizationTest.kt`.
- Added: this report.

`./gradlew test :prober:test build --no-daemon --console=plain`: PASS; backend 68 tests,
prober 35, zero failures. `git diff --check`: PASS.

Regression test covers rotating endpoint identity/current config, TUN/routing normalization,
DNS/REALITY preservation, ambiguous names, unnamed profiles and insecure-TLS rejection using
a local fake HTTP server under tests only. No runtime/static catalog substitute was introduced.

The full Android evidence and exact native dependency inspection are in
`/Users/busnet/Documents/SpdVpn/docs/premium/connection-diagnosis-20261022.md`.
