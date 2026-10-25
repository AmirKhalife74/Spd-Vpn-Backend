# Server-driven Premium plan catalog

Local implementation update, 2026-10-25. No production deployment or account administration.

## Existing architecture and actual gap

The backend already owned `Plan` documents in SQLite's `plans` table, seeded the existing
MONTH_1/MONTH_3/LIFETIME definitions once (`INSERT OR IGNORE`), exposed public `GET /v1/product`,
and managed definitions through authenticated `GET /admin/api/plans` and `PUT /admin/api/plans/{id}`.
The private-plan extension adds the missing `POST /admin/api/plans` creation operation;
it reuses that model/store and introduces no billing service or SQL migration.

Android already had `ProductPlan`, `ProductResponse`, `AccountApi.product()` and plan cards.
It was NOT using runtime hardcoded fallback plans. However it requested product only at startup,
silently ignored failures, used account loading state instead of product loading, never fetched
on plan-screen entry, and the empty-state retry callback was empty. The sole sample priced plan
was a Compose preview; that preview was removed to avoid confusion. Backend plans had no pricing,
localization maps, publication flag or configurable display order; the panel could not edit these.

There is no Google Play Billing dependency, configured payment provider, transaction/checkout,
receipt/webhook verification or automatic paid activation. The previous Telegram-labelled CTA
was only an external link and did not prove payment. The optional server-managed **Contact to
Purchase** action is explicitly external/manual; see [manual-purchase.md](manual-purchase.md).
About SPD's official Telegram channel in Android Settings is independent and unchanged.

## API reused and additive contract

`GET /v1/product` remains public, `Cache-Control: no-store`, and uses the existing wrapper:
`success/data` containing `serverTimeEpochMillis`, `plans`, `premiumVpnConfigured`, and optional
`telegramBotUsername`. The last two fields retain their previous meaning; VPN configuration
availability is not payment-provider availability. No user/admin credentials are needed to view plans.

Each public plan retains existing fields and adds optional/defaulted metadata:

| Field | Contract |
| --- | --- |
| id | Existing stable ID; never computed from price/title/description |
| title / description | Original fallback text; description may be empty |
| localizedTitles / localizedDescriptions | Optional map with `en` and/or `fa`; client uses current language then original fallback |
| durationDays / lifetime | Existing timed or lifetime definition; lifetime means no time expiry, NOT unlimited quota |
| quotaBytes | Existing nullable independent quota |
| enabled | Existing administrative availability flag |
| published | Public-catalog visibility; default true for old documents, independent of manual admin assignment |
| isAssignable | Manual-admin assignment permission; default true for old documents, independent of publication |
| priceMinor | Nullable integer 0..9007199254740991; unknown is null, never assumed free |
| currency | Nullable uppercase supported ISO-4217 code; must be present iff priceMinor is present |
| displayOrder | Integer 0..100000, ascending; ties use stable id |

Amounts are **ISO currency minor units**, not arbitrary whole units or localized labels. USD
12345 means 123.45 USD, IRR 12345 means 123.45 IRR (rial, not toman), JPY 12345 means 12345 JPY,
KWD 12345 means 12.345 KWD. The client uses exact BigDecimal conversion and localized number
formatting, displaying the explicit currency code in an LTR technical value. No FX conversion,
toman conversion, fabricated price or checkout is introduced.

Public plans are enabled, published and valid under the same policy as admin updates. Plans
without a configured price may still be published as **informational** plans;
they display 'Price not configured'. There is no in-app payment/checkout; an optional external
contact link permits discussing a selected plan with the administrator without automatic activation.
There is no account/platform eligibility segmentation in
the current product contract, and none was invented here. No internal account/admin/credential
metadata is part of a plan response. A real empty list remains an empty list.

Admin validation preserves the existing three IDs and permits additional stable IDs matching
`[A-Za-z0-9][A-Za-z0-9_-]{0,63}`. IDs do not change when names, prices or quotas change.
All plans use the same duration/quota/lifetime, paired pricing/currency, bounded order and
`en`/`fa` validation (title 120, description 500 characters). A lifetime plan has null duration;
a timed plan has 1..3650 days, irrespective of ID. No example trial/VIP/gift plan is seeded.
The panel supports creation and editing; new drafts default to unpublished and assignable.
Routes retain admin-cookie, Origin and CSRF checks. No authentication policy changed.

At most **three active, published, valid** plans can be public. The limit is checked inside
the same SQLite transaction as create/update, including reactivation, so concurrent writes
cannot publish a fourth plan. Rejection is HTTP 400 `PUBLIC_PLAN_LIMIT`. Inactive published
definitions do not consume a public slot until activated. Android does not hardcode IDs or
truncate to three; it displays the backend's dynamic list. If externally modified storage
violates capacity, product fails with 503 `PLAN_CATALOG_INVALID`, not an arbitrary subset.

## Storage and subscription compatibility

Metadata stays in the existing JSON document column, so SQLite schema remains version 1 and
001_premium.sql is unchanged. Old documents decode with null prices, empty translation maps,
published=true, isAssignable=true and the historical default order (MONTH_1, MONTH_3, LIFETIME). Existing definitions
are not overwritten or republished on restart. Admin listing includes hidden/disabled plans.

Updating plan presentation, publication, price or description does not mutate account targets,
confirmed PasarGuard state, sessions, expiry or quota. Existing account authorization does not
depend on a plan still being published/enabled/assignable. Creation, renewal, plan change and
failed-link repair require a valid enabled + isAssignable plan; otherwise HTTP 400
`PLAN_NOT_ASSIGNABLE`. Public plans need not be manually assignable, and private plans may be.
Admin selectors show only active assignable options, including private ones; current account
details remain visible when their plan is no longer eligible. An unavailable current option is
not silently replaced with a different plan. Legacy PUT bodies omitting `isAssignable` preserve
the stored assignment flag atomically. Published only gates public display. Renew/change/disabled rules, including
explicit lifetime-to-timed conversion, are unchanged. Selection on Android grants nothing.

`quotaBytes` is an absolute **byte** ceiling: 10 GB = 10000000000 bytes, whereas 10 GiB =
10737418240 bytes. Null means unlimited. Existing PasarGuard requests send `data_limit` and
`no_reset` on create; update/retry applies an absolute target, never resets observed usage.
SPD verifies returned limits and denies Premium catalog/profile access at observed exhaustion.
This is not a new traffic meter or a client-only display quota. Physical tunnel-level traffic
enforcement belongs to the real PasarGuard/Xray infrastructure; local adapter/service tests do
not certify that infrastructure. Live traffic exhaustion testing for this change is NOT_RUN.

See [private-plan verification and rollout requirements](assignable-plans-20261025.md).

## Android request lifecycle

The existing application-scoped AccountRepository exposes independent public-product loading,
loaded/error and last-successful-update state through its existing AccountUiState. Public
fetches do not carry tokens or invalidate the private session. Startup fetch is isolated from
account-owned cancellation; late public data cannot restore account/profile state after logout.

The Premium route invokes `onPlansOpened()` in a LaunchedEffect, not during recomposition.
Successful catalogs are fresh for 60 seconds (injected monotonic clock); re-entry after that
refreshes. Manual refresh/retry forces a request. A mutex coalesces in-flight requests and the
refresh control is disabled while loading. Failures preserve only the last received catalog and
timestamp, explicitly mark it stale, and allow retry. Empty/error/success/loading are distinct.
The UI uses backend ordering/translations and semantic Material colors for light/dark and RTL/LTR.

## Payment prerequisites (not implemented)

Choose and explicitly configure a real provider and permitted distribution-channel workflow;
define server-created orders tied to authenticated accounts and a plan/price snapshot, idempotent
provider callbacks/receipt verification, reconciliation/refund handling and server-authorized
activation. Google Play availability must be decided explicitly, not assumed. Only after that
work should an actual checkout UI be introduced. Manual contact is not payment verification.
No client action should grant entitlement.
Existing admin-issued accounts can continue signing in and using their verified subscription.

Backend and Android must be released separately later to expose the new optional fields in
production. This task did not deploy, access production, install an APK, commit or push.
