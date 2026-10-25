# Premium identity — production verification and minimal correction

Verified 2026-10-24, read-only SSH plus the owner's explicitly authorized test-account API
session. **No deployment, restart, APK installation, provisioning, direct database write,
PasarGuard administration, commit or push was performed.** Login/logout of the temporary
user API session were the only session operations. Normal server-side session and entitlement
observation bookkeeping performed by those existing APIs is not bypassed. Credentials were entered with hidden
terminal input; neither credentials, tokens, subscription URLs nor private Xray JSON were
printed or saved.

## Confirmed production artifact (not inferred from local source)

- SSH alias `spdvpn`, existing configured deployment `/opt/spdvpn`.
- Container `spdvpn-api-1`, running image
  `sha256:50b29ccdd7d4d67c73d92c20d9fc926b75bad916f295118f48e91312776c4d4d`.
- Tag `spdvpn/backend:premium-tun-20261022`, started `2026-10-22T10:31:06.9569762Z`;
  container health **healthy**. The immutable image ID was read from `docker inspect`;
  Compose's historical labels were not treated as authoritative image IDs.
- Active Compose files:
  `/opt/spdvpn/releases/premium-tun-20261022/compose.yaml` and `compose.premium.yaml`.
  Existing `.env` is present. Only environment **key names**, not secret values, were inspected.
- Existing named volume `spdvpn_spdvpn-data` is mounted at `/app/data`.
- Running `/app/app.jar` SHA-256:
  `02e99a2502b04b705bc9d006daf8fd09d4d52a83bbf1ca24d33cf07767f24282`.
  The release-directory `spd-vpn-backend-all.jar` has the same hash.
- `javap -p -c -v` on **the running container's JAR** confirms `HttpPasarGuard.profiles`
  concatenates protocol, endpoint host, port, transport and serialized remarks, then hashes
  and takes 32 characters. It contains neither `logical-v2|` nor `endpoint-v2|`.
  Production is therefore definitely running the historical algorithm.
- Safe `premiumProfile` identity diagnostics are absent from the inspected running class/log
  sample; absence of a log alone was not used as proof of the policy.
- `/health/live` is HTTP 200 (plain text); `/health` is HTTP 404 in this deployment.
  Do not use the latter as a rollout liveness test. No health routes were changed here.

The deployed class hash is
`38264904431f4dee41d0d516043d2fc9560f74b5b22ff18f942841768fc1266d`.
The private local audit directory is `/private/tmp/spd-premium-identity-20261024.XIWZiz`.
It retains source/status snapshots, sanitized results and artifact disassembly; it is not
a release input and must not be committed or copied to production wholesale.

## Fresh production reproduction

Two authenticated `GET /v1/premium/servers` requests for the **same test account** returned
17 rows each. The multiset of display name, protocol, transport and country metadata was
identical, with one row per descriptive group, but **9 IDs disappeared and 9 new IDs appeared**.

The audit requested each of the 17 IDs from the first response using the normal authenticated
`GET /v1/premium/servers/{id}/profile` endpoint:

- 8 HTTP 200 responses, all returning the exact requested ID.
- 9 HTTP 404 responses with structured `NOT_FOUND`.
- Every successful response's ID matched the historical formula computed privately from its
  profile; none matched logical-v2. All successful profiles had nonblank names.
- One ID still present in the second catalog returned `NOT_FOUND` during profile resolution:
  `5fc6f71f877b7e46c0cbed4eaf89c01c`.
- A changed descriptive group, for example, moved from
  `d4a5da0c6760b1aa19d7d7cc49fdd3c0` to `45f6cff65dbce44c9f35532fb30de467`.
- The measured set was VLESS. This is not evidence of a VLESS-only defect; the common adapter
  path and regression tests cover VLESS, VMess, Trojan and Shadowsocks.

This confirms real identity drift and the independent-fetch race in **today's production**,
not just a historical report. No Android cache is needed to reproduce the backend 404.
Private configurations for failing lookups cannot be obtained through that failing endpoint;
the audit therefore does not claim to distinguish IP rotation from port/raw-remarks changes
for every individual failed row, or prove that every failing row has a reliable upstream name.

## Exact origins and security boundaries

- Android `AccountRepository.loadXrayProfile`, in
  `/Users/busnet/Documents/SpdVpn/androidApp/src/main/java/io/spdvpn/android/account/AccountRepository.kt`,
  forces account/catalog refresh, checks entitlement and rejects the captured selected ID if
  absent: **local `PREMIUM_SERVER_CHANGED`, before profile HTTP**. The live catalog difference
  supplies this prevalidation condition; new repository tests exercise it without connecting.
- Backend `src/main/kotlin/app/spdvpn/backend/premium/PremiumService.kt`, `catalog` and `xray`,
  call the same owner-scoped `pg.profiles(a.pgId)` independently. `xray` returns `missing()`
  (`404 NOT_FOUND`) when the fresh subscription contains no exact requested ID. This path was
  reproduced live above. Authorization is checked before and after upstream fetching.
- `PremiumRoutes.kt` obtains the owner from the user bearer session. Malformed IDs are 400;
  absent/invalid authentication is 401; another owner's missing profile is 404. An upstream
  subscription 404 is instead `PASARGUARD_USER_MISSING`/503.
- `PasarGuard.kt` is the one parser and identity source used by both endpoints. Android keeps
  the returned IDs unchanged. Premium catalogs are account-owned in-memory state, not the Free
  SQLite server cache. Only last selected Premium ID is remembered. Transient cached data does
  not authorize a new profile, and no Free/Premium or subscription/profile data source is mixed.

## Minimal correction and local state

The required runtime correction **already exists in local `PasarGuard.kt`** from the earlier
investigation and has not reached production. This task retained it rather than adding a
second identity policy, unsafe alias registry, cached profile fallback, retry loop or client
membership bypass:

```
nonblank sanitized name: sha256(logical-v2 | protocol | transport | alias).take(32)
otherwise:               sha256(endpoint-v2 | protocol | host | port | transport).take(32)
```

Named unique entries therefore survive host/port/credential rotation, refresh and process
restart while the profile endpoint still obtains their current private configuration.
Duplicate logical IDs are excluded with `groupBy`/`singleOrNull`, never arbitrarily chosen.
Renames/protocol/transport changes are new identities; callers must refresh and explicitly
use a current ID. Removed/ineligible/unauthorized profiles remain denied.

**No new production-code behavior was necessary or changed in this task.** The verified local
correction was used as the baseline; this task adds missing regressions and deployment evidence.
Android requires no workaround or new APK for this identity correction. A **backend deployment
is required**, followed by a catalog refresh/reselection once because the policy changes IDs.
The deployment is intentionally not performed without separate approval.

Candidate JAR built from the preserved current local source:

`build/libs/spd-vpn-backend-all.jar`

SHA-256 `7a9b8cd117b7b913fe598333119915de68ed53adaf8e0477fb18429c29604f77`.
It contains both logical-v2/endpoint-v2 markers. Candidate adapter class SHA-256:
`815ce5cce3c804fd1887868f0758a8a8b48862c55a450d3903cefa7610ede098`.
Embedded `db/migrations/001_premium.sql` is byte-identical to production. No migration or
persistent identity mapping is introduced by this correction.

## Changed files in THIS task

Backend root `/Users/busnet/Downloads/SPD-VPN-Backend-v0.1.0`:

- `src/test/kotlin/app/spdvpn/backend/premium/PremiumServerIdentityTest.kt`:
  four additional adapter regressions for independent IP/port rotation, subscription ordering
  and additions, rename/removal/ambiguity, and explicit unnamed fallback behavior.
- `docs/premium/identity-production-verification-20261024.md` (this report).
- `docs/premium/identity-rollout-20261024.md` (approval-gated rollout/rollback instructions).

Android root `/Users/busnet/Documents/SpdVpn`:

- `androidApp/src/test/java/io/spdvpn/android/account/AccountRepositoryTest.kt`:
  two additional regressions for one-time policy-upgrade stale selections and repeated fresh
  catalogs/profiles without caching private JSON or substituting another ID.
- `docs/premium/server-id-investigation-20261024.md`: follow-up link to verified production evidence.

The pre-existing logical-v2 implementation, safe diagnostic sinks, tests and all unrelated dirty
Android/backend work were preserved. The wider Git status includes earlier uncommitted work;
it must not be attributed to this task.

## Verification

```
# Backend
JAVA_HOME=<existing JDK 17> ./gradlew test :prober:test buildFatJar --no-daemon --console=plain
# Android; all four release-signing environment variables explicitly unset
JAVA_HOME=<existing JDK 17> ./gradlew :androidApp:testDebugUnitTest \
  :androidApp:testReleaseUnitTest --no-daemon --console=plain
# Both repositories
git diff --check
```

- Backend: **78 passed**, zero failures/errors/skips.
- Prober: **35 passed**, zero failures/errors/skips (up-to-date).
- Android debug: **145 passed, 1 skipped**, zero failures/errors.
- Android release: **145 passed, 1 skipped**, zero failures/errors.
- The existing opt-in isolated-localhost account integration test is skipped; its separate
  localhost backend/password-file setup was not provided. Fresh production API reads above
  were a separate authorized test, not that local fixture.
- Fat JAR build and both diff checks: PASS.

Regression coverage, including retained tests: endpoint IP/port rotation, adapter restart,
subscription refresh/reordering, all four accepted protocol families, rename, duplicates,
unnamed entries, removed/ineligible entries, other-owner and invalid-token rejection, stale
Android selections, no hidden substitution, retained-cache denial and exact response-ID checks.

## Remaining edges / not claimed

- Unnamed rotating entries cannot be made stable without a reliable upstream identifier;
  endpoint-v2 is explicitly a conservative fallback, not a durable reconciliation registry.
- Renames and newly ambiguous names deliberately invalidate a selection. There is no unsafe
  matching by list position, guessed endpoint, shared credential, or a formerly cached profile.
- Name sanitization/truncation can create a collision; such a group is excluded, not guessed.
- Upstream removal, eligibility/TLS-policy change and entitlement revocation still reject a
  profile even after logical-v2 rollout. This is expected, not justification to bypass checks.
- Live success **after deployment**, restarts, Android connection and tunnel/traffic tests:
  NOT_RUN in this task, because deployment/restart/install/connection were not authorized here.
- The live drift's particular IP-versus-port/raw-remarks changes and whether all failed entries
  have stable upstream names were not exposed by the API; those per-entry facts are not invented.

See [identity-rollout-20261024.md](identity-rollout-20261024.md) for the gated operational steps.
