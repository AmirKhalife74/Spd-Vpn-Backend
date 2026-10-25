# Consolidated Premium Backend API release — 2026-10-25

**Prepared, not deployed.** Production access for this audit was read-only: Docker/
Compose inspection, file/checksum reads, logs and public GETs. No production login,
PUT, account operation, image build/tag, file upload/backup, restart or deployment.

## Readiness and execution gates

Local code/artifact verification is PASS. Ready for deployment review/approval,
**not permission to execute**. Before any future production switch:

1. Obtain explicit rollout approval and a brief administrator plan/settings write
   freeze. Recheck the exact current image/configuration and current purchase state.
2. Create and verify a **fresh backup of the current image/configuration/data**, not
   the older pre-CORS backup. Retain current `dea62...` as the immediate rollback.
3. Validate all stored plan documents against the candidate on an isolated consistent
   DB copy. Public product currently has three plans; today's unauthenticated audit
   did not read the complete authenticated admin plan inventory.
4. Have authorized admin and Premium test account access via hidden input for live
   session/catalog/profile checks. Authenticated production checks were NOT_RUN in
   this preparation, to avoid session/audit/customer-data writes.
5. Do mutating publication/private-assignment tests only in isolated test storage
   with production PasarGuard credentials absent, unless separate test-data/provisioning
   approval is given. Never change a customer's plan merely to test a publication slot.

There are no failing local tests/builds. Real packet-level PasarGuard quota exhaustion
remains NOT_RUN without an authorized non-production node environment; do not claim
that new trial allowances have been physically exercised on real nodes.

## Pinned artifact and provenance

- Local JAR: `build/libs/spd-vpn-backend-all.jar`, 42,304,591 bytes.
- SHA-256: `ee899647e2236b0906e7fbdc3f0e93c2463a11d7625cdd9b0ade484a3490f95d`
- Frozen copy: `/private/tmp/spd-consolidated-release-20261025.kGAMUD/release/spd-vpn-backend-all.jar`
- Git HEAD: `98d54848f1b39c0f17f4b6a0989798a602ddafc2`, **plus reviewed uncommitted files**.
  HEAD alone is not this artifact's provenance. No index/branch/commit change.
- Runtime: Kotlin 2.3.20, Ktor 3.5.2, JVM target/toolchain 17; existing SQLite JDBC
  3.49.1.0 and Bouncy Castle 1.80. No new dependency change versus the deployed JAR.
- The release directory contains SHA256SUMS and a source/artifact/test manifest.
  Full scope and source hashes are in the restricted audit directory outside Git.
- A future Docker image is **NOT_BUILT**; no new image digest is claimed. Build it
  only after approval from this pinned JAR, then record/verify its actual image ID.

## Complete release scope

The seven requested features are all in the candidate. CORS, manual purchase,
server-driven plans, duration/quota integration and logical-v2 already exist in the
current production baseline; this is not a replacement with an older release.

Actual new runtime source delta against production is exactly:

1. `src/main/kotlin/app/spdvpn/backend/premium/PremiumModels.kt`: optional/defaulted
   `isAssignable=true`, retaining existing enabled/published fields.
2. `src/main/kotlin/app/spdvpn/backend/premium/PlanCatalogPolicy.kt`: valid custom IDs,
   independent assignment/publication, transactional three-public-plan capacity rule.
3. `src/main/kotlin/app/spdvpn/backend/premium/PremiumService.kt`: authenticated admin
   plan creation/update, capacity enforcement, eligibility for create/renew/change/link.
   Existing account expiry/quota targets and connection eligibility are not re-derived
   merely because publication/assignment/active flags change.
4. `src/main/kotlin/app/spdvpn/backend/premium/PremiumRoutes.kt`: POST plan creation;
   PUT preserves omitted legacy assignment flags. Admin cookie/Origin/CSRF retained.
5. `src/main/resources/admin/app.js`: reusable create/edit forms, active/public/manual
   switches, eligible assignment selectors and explicit byte-unit quota guidance.

Git audit before adding this report: **64 dirty/untracked files**, all classified:

| Classification | Count | Disposition |
| --- | --- | --- |
| Runtime source/resources | 23 | Includes already-deployed premium/panel, Application and CatalogService changes |
| Verification scripts/tests | 13 | Source support only; no test data in production JAR |
| Documentation Markdown | 11 | Source/release support, not runtime payload |
| Build/sample configuration support | 7 | .env.example, .gitignore, README, Gradle/Compose and npm manifests; no production overwrite |
| Excluded references/generated assets | 10 | Input ZIP, eight historical screenshots, PasarGuard.kt.before-tun-fix |

This report adds one documentation file; the final scope manifest includes it.
The exact file-by-file list and SHA-256 values are in `uncommitted-scope.json`.
Excluded from transfer/build context: the input panel ZIP, `.before-tun-fix`, screenshots,
`.git`, real `.env`, data/SQLite files, caches, reports, node_modules and build outputs
other than the reviewed JAR. Android, engines and deployment credentials are not included.

163 critical artifact entries are byte-identical to production, including Application's
CORS setup, PremiumSecurity, HttpPasarGuard/logical-v2, purchase/panel stores, cache/health
formats and SQL migration. Other changed entries are confined to Premium implementation
and admin JS; no unexpected vendor/dependency/resource change was found.

## Backward compatibility / customer preservation

- Public GET `/v1/product` retains `success/data`, fields, pricing/localization,
  display ordering, no-store and `explicitNulls=false`. `isAssignable` is additive;
  old clients ignore it. Android remains dynamic, with no hardcoded count/IDs.
- Existing names `enabled` and `published` remain canonical active/public flags.
  No competing isActive/isPublished aliases. Old stored plans default assignable=true.
- New POST `/admin/api/plans`: admin-only, 201. PUT edits matching stable IDs; legacy
  bodies omitting assignment permission preserve its stored value atomically.
- Public catalog includes only valid active published definitions, maximum three.
  Writes exceeding capacity return 400 PUBLIC_PLAN_LIMIT. Invalid out-of-band
  overcapacity storage fails closed with 503 PLAN_CATALOG_INVALID, never arbitrary truncation.
- New assignments require active + assignable + valid, including unpublished plans;
  otherwise 400 PLAN_NOT_ASSIGNABLE. User bearer tokens cannot grant private plans.
- No SQL/schema migration, account rewrite, password reset, token invalidation,
  automatic plan repricing/reassignment or quota/usage reset. Existing subscriptions
  continue even if their plan becomes inactive, unpublished or non-assignable.
- Quota remains an absolute byte ceiling: 10 GB = 10000000000, 10 GiB = 10737418240;
  null unlimited. Existing PasarGuard data_limit/no_reset/readback/denial behavior
  remains; lifetime concerns time, not unlimited traffic. Renewal arithmetic unchanged.
- Isolated old-production-JAR -> candidate -> candidate-restart smoke verifies
  unchanged account IDs, password hashes, plan documents, account targets and admin
  records; old sessions/password remain accepted and purchase settings persist.
  The deployed legacy ProductResponse serializer also decodes actual candidate JSON.
- No new environment variable. Do **not** copy local sample Compose/.env over production.

## Production observations (read-only)

- Current API image: `sha256:dea62d3297008b1089845f98316006bc51fa1755ff3a574e5c463def8360e861`
- Tag: `spdvpn/backend:admin-put-cors-20261025T055445Z`
- Container: `75e7030ac9068eaea33ea2133a16af2a2b59ae0b9f072843b1a684a517c9d0f6`
- Started: `2026-10-25T05:58:29.513792405Z`; healthy. `/health/live`: HTTP 200,
  both loopback and public HTTPS. Current image is available locally by digest.
- Running JAR SHA: `e5a7731d6e46c209d79531b1b3e4b3caa6498b820b233526092233ed0e28604d`
- Public catalog: three plans, MONTH_1 / MONTH_3 / LIFETIME.
- **Manual purchase is now enabled and a real URL is configured. Preserve both
  exactly. Older deployment reports saying false/null are historical, not the baseline.**
  The URL itself and credentials were not printed/saved in this audit's summaries.
- Compose project/service: spdvpn/api. Working directory and env file:
  `/opt/spdvpn/releases/premium-tun-20261022` and its `.env`.
- Active Compose files, in order:
  1. `/opt/spdvpn/releases/premium-tun-20261022/compose.yaml`
  2. `/opt/spdvpn/releases/premium-tun-20261022/compose.premium.yaml`
  3. `/opt/spdvpn/releases/premium-identity-20261024T083322Z/compose.identity.yaml`
  4. `/opt/spdvpn/releases/plans-purchase-20261024T125132Z/compose.purchase.yaml`
  5. `/opt/spdvpn/releases/admin-put-cors-20261025T055445Z/compose.admin-put.yaml`
- `compose config --quiet`: PASS; resolved environment matches running container.
  Config checksums recorded privately; no sensitive resolved config was printed.
- Admin Origin HTTPS exact, insecure-local false. Volume spdvpn_spdvpn-data ->
  `/app/data`, network spdvpn_default. Paths: premium.sqlite, panel-content.json,
  vpngate-cache.json, server-health.json. Versions: SQLite header 1, panel 1,
  catalog 3, health 2; candidate formats are compatible.
- Prober container `12fb58e2661328d2786ac5ea760e7f1f608110eefb867bef219f519a64dfd36a`,
  image `sha256:6cbeb354f13e0efc46a8d311ecf1d4c94fba29b6ba638d9574826d3f3d0be893`,
  started `2026-10-21T12:51:07.339889206Z`; unchanged.
- Free disk observed about 5.24 GB. Recheck disk/memory before building/canary.
- Existing protected backup `/opt/spdvpn/rollbacks/admin-put-cors-20261025T055445Z`
  contains the **older 05391... image**, not today's immediate rollback. Its settings
  also predate the newly enabled purchase contact. Do not reuse it as a fresh baseline.
  Do not run the historic switch_remote.py either: it pins the older rollback image,
  JAR and false/null settings assumptions. New execution must use freshly captured state.

## Tests executed for this preparation

```sh
JAVA_HOME=/Users/busnet/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home \
  ./gradlew :test :prober:test build buildFatJar --rerun-tasks --no-daemon --console=plain
npm run check
npm test
node --check scripts/admin-browser-test.mjs
npm run test:browser  # disposable loopback DB, generated fixture password, no PasarGuard
git diff --check
```

Backend **105 PASS**, Prober **35 PASS**, JavaScript **8 PASS**, build/fat JAR PASS.
All 21 Gradle tasks executed. Browser E2E PASS: login/CSRF, private plan creation and
assignment, fourth-public-plan rejection, assignment selector, pending/failed state,
navigation, audit, responsive layout, logout; nine screenshots outside the repository.
Old-binary contract and SQLite/customer/session/purchase compatibility smoke PASS.
No production data/credentials were used by local tests. Actual PasarGuard traffic
exhaustion and fresh authenticated production catalog/profile checks: NOT_RUN here.

## Approval-gated deployment steps — INSTRUCTIONS ONLY

1. Re-run read-only preflight. Stop on image, container, config, mount, schema,
   purchase state or health drift; review the new baseline instead of overriding it.
   Freeze administrator plan/settings edits until acceptance. Coordinate test accounts.
2. Create unique UTC-stamped release/rollback directories under /opt/spdvpn, mode 0700.
   Copy all five current Compose layers and the real env with original metadata into
   the protected backup; capture private container/image/resolved configuration.
   Snapshot panel/catalog/health files atomically and record checksums. Use SQLite's
   consistent online backup API on a read-only source; never cp a live SQLite/WAL pair.
   Host volume permissions may require container-level reads and the previously
   verified bundled SQLite backup helper, not sudo/permission weakening.
3. Tag current **dea62...** as `spdvpn/backend:rollback-consolidated-$stamp`, save its
   image archive, validate descriptor/layer/checksum integrity and tag->image ID.
   Verify frozen backup Compose resolves identically to current Compose. Verify an
   image-only rollback changes only api.image and can use the exact existing data.
4. Transfer only the frozen JAR into the new release directory. Verify SHA-256 locally,
   after transfer and immediately before build. No source/context-wide rsync or env copy.
5. Build only API while current API runs, with the exact rollback runtime as base:

   ```dockerfile
   FROM spdvpn/backend:rollback-consolidated-<approved-UTC-stamp>
   COPY --chown=spdvpn:spdvpn spd-vpn-backend-all.jar /app/app.jar
   ```

   Use docker build --pull=false --network=none, unique candidate tag and iidfile.
   Verify only the JAR filesystem layer changed, runtime defaults unchanged and actual
   candidate image ID recorded. Do not build/pull Prober or use the old default source
   Dockerfile context (production releases contain a prebuilt JAR, not complete source).
6. Run isolated canary verification before switching, then repeat as needed after the
   switch against the exact candidate artifact/image. Separate DB/data, loopback-only
   unpublished port, fresh fixtures, production PasarGuard credentials and prober secret
   **absent**. Never start a cloned pending-operations worker with production credentials.
   Check resources first; use a separate authorized staging host if capacity is insufficient.
7. Add a final override containing only `services.api.image`. Resolve full Compose
   privately and assert equality with baseline after normalizing only that image value.
   Keep all original env, volumes, networking, health, security and resource settings.
8. Recreate only API with --no-deps --no-build --pull never. Bounded Docker health and
   HTTP checks: 180-second monotonic deadline, HTTP timeout 2 seconds. Any startup,
   health, configuration or critical security failure triggers the rollback below.
9. Verify the running JAR hash, public JS hash/version, matrix below and unchanged
   Prober image/container/start time. Test one ordinary API-only restart for settings/
   sessions persistence only if included in explicit rollout approval. Keep backups.

After steps 1–7 are verified and approval is granted, the Compose command shape is:

```sh
# These are future commands, not executed by this preparation.
: "${spd_release:?Set the verified release path from the approved preparation}"
spd_base=(docker compose -p spdvpn \
  --project-directory /opt/spdvpn/releases/premium-tun-20261022 \
  --env-file /opt/spdvpn/releases/premium-tun-20261022/.env \
  -f /opt/spdvpn/releases/premium-tun-20261022/compose.yaml \
  -f /opt/spdvpn/releases/premium-tun-20261022/compose.premium.yaml \
  -f /opt/spdvpn/releases/premium-identity-20261024T083322Z/compose.identity.yaml \
  -f /opt/spdvpn/releases/plans-purchase-20261024T125132Z/compose.purchase.yaml \
  -f /opt/spdvpn/releases/admin-put-cors-20261025T055445Z/compose.admin-put.yaml)
"${spd_base[@]}" -f "$spd_release/compose.consolidated.yaml" config --quiet
"${spd_base[@]}" -f "$spd_release/compose.consolidated.yaml" \
  up -d --no-deps --no-build --pull never --force-recreate api
```

Use SSH alias spdvpn with ControlMaster=no / ControlPath=none / BatchMode=yes;
do not change SSH configuration. Never dump full compose config, inspect env or logs.
No compose down, prune, Caddy/firewall/SSH modification, Android deployment or DB restore.

## Post-deployment acceptance matrix

| Check | Expected / safe scope |
| --- | --- |
| Docker health + loopback/public /health/live | healthy and HTTP 200, bounded deadline |
| Running artifact / identity | exact new JAR SHA; logical-v2 preserved, safe policy diagnostic |
| GET /v1/product | success/data, <=3 valid active published plans, correct ordering, original plan fields preserved |
| Manual purchase configuration | exact current enabled flag and URL unchanged; never replace with false/null or example URL |
| Authenticated admin GET + idempotent PUT | 200; send the freshly read same purchase settings, not a test URL; re-read to detect concurrent edits before writing |
| Same valid session, CSRF absent/incorrect | 403 CSRF_INVALID |
| Missing/foreign/HTTP Origin | 403 ORIGIN_INVALID; do not treat Referer/forwarded headers as substitutes |
| Cookie absent or user bearer used for admin | 401 TOKEN_INVALID |
| Fourth eligible public plan | isolated canary 400 PUBLIC_PLAN_LIMIT with no row inserted; concurrent writes cannot exceed three |
| Private active assignable plan | canary create 201; absent public product; admin user assignment 202 PENDING, requested bytes/duration correct |
| Inactive/nonassignable plan | canary create/renew/change/link selection 400 PLAN_NOT_ASSIGNABLE |
| Existing account preservation | canary flags edits do not change prior target, password, session or entitlement; no customer plan edited |
| Premium authorized test account | two current catalogs, stable unique IDs, profile resolution for every eligible entry, returned server.id matches requested ID |
| Restart persistence (if approved) | same actual purchase settings, old sessions valid, unchanged production data paths |
| Prober and configuration | exact original IDs/start time, env, mounts, network and resource/security settings |

Use hidden input and bounded sanitized summaries only; never print cookie/CSRF/access/
refresh tokens, URLs/private profiles or credentials. A 202 PENDING is not successful
PasarGuard provisioning. Network/upstream rename/duplicate/removal failures must be
distinguished from a candidate regression; never guess another server or bypass membership.
Do not run a destructive fourth-plan/private-user probe against customer storage;
there is deliberately no automatic delete endpoint to erase a mistakenly created plan.

## Rollback procedure / semantic boundary

If initial health/config/security/profile acceptance fails **before new plan writes
are enabled**, immediately recreate only API from the fresh frozen configuration and
the verified current-image rollback tag. Keep the existing volume/data in place:

```sh
# spd_backup is the fresh, approved rollout backup, NOT the historic 05391... backup.
: "${spd_backup:?Set the fresh verified rollback backup path}"
spd_frozen=(docker compose -p spdvpn \
  --project-directory /opt/spdvpn/releases/premium-tun-20261022 \
  --env-file "$spd_backup/.env" \
  -f "$spd_backup/config-0.yaml" -f "$spd_backup/config-1.yaml" \
  -f "$spd_backup/config-2.yaml" -f "$spd_backup/config-3.yaml" \
  -f "$spd_backup/config-4.yaml")
"${spd_frozen[@]}" -f "$spd_backup/compose.rollback.yaml" config --quiet
"${spd_frozen[@]}" -f "$spd_backup/compose.rollback.yaml" \
  up -d --no-deps --no-build --pull never --force-recreate api
# Require image ID == dea62d3297008b1089845f98316006bc51fa1755ff3a574e5c463def8360e861,
# healthy + HTTP 200, preserved current purchase settings and unchanged Prober.
```

No schema change means no ordinary database/snapshot rollback is required. Never
restore a stale DB or historical false/null panel backup over legitimate newer data.
Stop if the fresh rollback archive/configuration/tag cannot be validated.

After operators start using new custom/nonassignable plans, rollback is **not fully
semantically transparent**: the old binary can read documents and existing account
targets but ignores isAssignable, rejects custom IDs for edits and hides custom public
IDs. Maintain the write freeze during initial acceptance. If new plans have already
been used, inspect that state and choose an approved compatibility-aware rollback or
forward fix; do not delete referenced plans/users or silently relax the new policy.
This risk is an explicit gate, not a reason to overwrite customer data.
