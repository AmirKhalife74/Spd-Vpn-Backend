# Public versus admin-assignable Premium plans

Local-only implementation and verification, 2026-10-25. Production was not accessed,
modified, deployed or restarted. No commit, push, APK installation or VPN connection.

## Findings / naming compatibility

The real Ktor backend uses SQLite `plans(id, document)` with serialized `Plan`
documents, schema version 1. Three definitions are seeded with INSERT OR IGNORE;
custom IDs were previously rejected by `PlanCatalogPolicy`, no create endpoint
existed, and manual assignment only checked `enabled`. Android already renders the
dynamic public `GET /v1/product` list; it needs no runtime/UI change for this feature.

To preserve the deployed contract, existing **enabled** is the active/isActive
property and existing **published** is the public/isPublished property. No conflicting
alias flags were added. New independent **isAssignable** defaults to true when absent.

| State | Public catalog | New manual assignment |
| --- | --- | --- |
| enabled + published + isAssignable | yes | yes |
| enabled + published + not assignable | yes | no |
| enabled + not published + isAssignable | no | yes |
| enabled + not published + not assignable | no | no |
| not enabled | no | no |

Definitions must also pass duration, quota, ID, text, localization, price/currency
and order validation. Public plans without a price retain the existing informational
behavior; selecting/opening the manual contact link grants nothing.

## Model / storage / endpoints

- Reuses Plan and the existing JSON document column. No DDL migration, schema bump,
  rewriting of old plans, password/account migration or new storage mechanism.
- Adds optional/defaulted `isAssignable: Boolean = true`. Existing documents and
  clients remain valid. Legacy PUT bodies omitting the flag preserve its stored value
  transactionally, so an old tab cannot accidentally re-enable manual assignment.
- `POST /admin/api/plans` creates a definition and returns 201 success/data. Stable
  administrator-selected ID: `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`. Existing ID: 409;
  no update/upsert disguised as creation. No example trial/VIP/gift plans are seeded.
- `PUT /admin/api/plans/{id}` edits a matching existing ID; nonexistent ID: 404.
  Renaming/repricing never changes that ID. No delete action was introduced.
- Cookie, exact Origin/Sec-Fetch-Site and CSRF requirements are unchanged for both.
  User bearer tokens cannot authorize admin writes or self-grant private plans.
- Creation, renewal, plan change and failed-link repair all check
  `enabled && isAssignable && valid` server-side; otherwise 400 PLAN_NOT_ASSIGNABLE.
- Existing stored account targets/confirmed entitlement do not depend on those flags.
  Unpublishing, deactivation or disallowing assignment does not revoke current users,
  cancel an already-authorized pending operation, or change their expiry/traffic.
  Renewal/new assignment still requires an eligible plan selected by an administrator.

Public `GET /v1/product` keeps success/data, no-store, existing fields and null omission.
Only enabled, published, valid plans appear, sorted by displayOrder then stable ID.
At most three such plans are allowed: create/update/reactivation checks are inside
the write transaction; overflow returns 400 PUBLIC_PLAN_LIMIT, including concurrent
publication attempts. Inactive published plans do not consume a visible slot.
Out-of-band storage corruption that creates four eligible public plans yields
503 PLAN_CATALOG_INVALID rather than silently selecting three arbitrary entries.
Android does not hardcode plan IDs or a three-item cap; it ignores the additive flag.

## Panel

Plans has one reusable create/edit form: stable ID, duration or lifetime, total-byte
quota, price/currency, en/fa titles/descriptions, display order, active, published and
manual-assignment switches. New form drafts are unpublished; no plan is persisted
until an authorized explicit submit. Structured server errors remain visible.

User creation, renewal/change and repair selectors include private eligible plans
and exclude inactive/non-assignable ones. Empty eligible lists disable submission
with an explanation. An existing account's ineligible current plan remains visible
in its details, but is not silently replaced with a different assignment option.
The manual purchase settings form/endpoints and external contact behavior remain unchanged.

## Quotas: implemented boundary and limits of verification

Canonical unit is **bytes**: decimal 10 GB = 10000000000; binary 10 GiB = 10737418240.
Null is unlimited. Existing account details may display the corresponding GiB value;
the editor labels its exact byte input and explains both units.

This feature reuses, not replaces, the existing enforcement integration:

1. An authorized assignment snapshots absolute expiry/quota in the account/operation.
2. HttpPasarGuard sends `data_limit` in bytes, `expire` and configured real groups.
   Create uses `data_limit_reset_strategy=no_reset`. Null SPD quota maps to upstream
   zero (unlimited); no traffic reset endpoint is called on renewal/update/retry.
3. The worker reads the actual returned user and confirms matching quota/expiry/status.
   Network or mismatch failures remain FAILED/PENDING, not a fabricated success.
4. Premium catalog/profile authorization reads upstream status/usage and rejects
   exhausted quota, even if status still says active. Time status is not faked as expired.
5. Actual packet-level termination/enforcement of an already-issued configuration
   belongs to the deployed PasarGuard/Xray nodes. It cannot be established by storing
   a quota in SPD or by these local HTTP/service tests.

Tests verify the 10000000000-byte upstream wire value and no-reset strategy, assignment
readback, exhaustion denial and unchanged usage after renewal/change. **Real PasarGuard
provisioning and physical traffic-exhaustion testing: NOT_RUN**, because no isolated,
authorized non-production PasarGuard/node environment was supplied. Production credentials,
groups, subscriptions and traffic were deliberately not used for this test.

## Verification results

- Backend: **105 tests PASS**, including 10 new service/route regressions. Existing
  identity, entitlement, manual purchase, public catalog and proxy/CSRF tests pass.
- Prober: **35 tests PASS**; no source changes. Final full rerun uses --rerun-tasks.
- Backend build and fat JAR: PASS.
- JavaScript syntax and helper tests: **8 PASS**.
- Android related unit tests: **42 debug + 42 release PASS** across ProductApiTest,
  ProductPlanPresentationTest, AccountRepositoryTest and PremiumPurchaseContactTest.
  Added only a compatibility test for a custom plan ID/additive assignment field;
  no Android runtime, UI, auth or engine changes.
- Real headless Chrome against disposable local Ktor/SQLite: PASS. Create private
  10 GB plan, reject a fourth publication, include/exclude assignment options,
  create user with that plan, navigation/audit/logout and existing responsive UI.
  No PasarGuard configuration: that browser's new user is correctly PENDING/FAILED,
  never reported as a successfully provisioned live VPN account.
- git diff --check: PASS in both repositories. Pre-edit snapshots are outside Git:
  `/private/tmp/spd-private-plans-20261025.NnUkG6`. Nine browser screenshots and test
  logs are there, not generated into source directories.

Commands (JDK 17):

```sh
# Backend repository
./gradlew :test :prober:test build buildFatJar --no-daemon --console=plain
./gradlew :test :prober:test --rerun-tasks --no-daemon --console=plain
npm run check
npm test
# Browser script uses a disposable loopback backend/DB, temporary fixture password,
# existing Chrome and SPD_SCREENSHOTS_DIR outside the repo. Never run on production.
npm run test:browser

# Android repository (the same four filters are supplied to each task)
./gradlew :androidApp:testDebugUnitTest \
  --tests '*ProductApiTest' --tests '*ProductPlanPresentationTest' \
  --tests '*AccountRepositoryTest' --tests '*PremiumPurchaseContactTest' \
  :androidApp:testReleaseUnitTest \
  --tests '*ProductApiTest' --tests '*ProductPlanPresentationTest' \
  --tests '*AccountRepositoryTest' --tests '*PremiumPurchaseContactTest' \
  --no-daemon --console=plain
```

## Exact files changed

Backend `/Users/busnet/Downloads/SPD-VPN-Backend-v0.1.0`:

- src/main/kotlin/app/spdvpn/backend/premium/PremiumModels.kt
- src/main/kotlin/app/spdvpn/backend/premium/PlanCatalogPolicy.kt
- src/main/kotlin/app/spdvpn/backend/premium/PremiumService.kt
- src/main/kotlin/app/spdvpn/backend/premium/PremiumRoutes.kt
- src/main/resources/admin/app.js
- src/test/kotlin/app/spdvpn/backend/premium/AssignablePlansTest.kt (new)
- src/test/kotlin/app/spdvpn/backend/premium/PlanManagementRoutesTest.kt (new)
- src/test/kotlin/app/spdvpn/backend/premium/PasarGuardAdapterTest.kt
- scripts/admin-api-test.mjs
- scripts/admin-browser-test.mjs
- docs/premium/plans.md
- docs/premium/android-api.md
- docs/premium/assignable-plans-20261025.md (this report)

Android `/Users/busnet/Documents/SpdVpn`:

- androidApp/src/test/java/io/spdvpn/android/account/ProductApiTest.kt

## Production release requirements (not executed)

1. Approval-gated API-only image rollout from the newly tested JAR, including bundled
   admin JS. Keep the prior image/configuration and a consistent online SQLite backup.
   Preserve env, volumes, networking and Prober. No new environment setting is required.
2. No schema migration, SQL rewrite or data deletion is needed. Existing definitions
   remain published/assignable by default; administrators explicitly create private
   plans and decide their quotas/duration. Do not seed examples into customer data.
3. Verify auth/Origin/CSRF, product <=3, private assignment and normal restart persistence
   with an isolated test account. Preserve logical-v2 and manual purchase configuration.
4. Coordinate/temporarily pause admin plan edits during rollback. Older binaries can
   read the additive documents but do not enforce isAssignable, cannot edit custom
   IDs under the old validator and hide custom public plans under the old allowlist.
   Existing account targets remain readable. Do not restore a stale database over
   later customer changes or delete custom plans referenced by accounts; prefer a
   reviewed forward fix if the feature has already been used.
5. Verify real node traffic-limit enforcement in an authorized non-production
   PasarGuard deployment before promising a live trial allowance. No Android release
   is necessary for the added server-side flag/custom IDs; compatibility tests pass.
