# Android / SPD account API contract

Source of truth inspected read-only:
`/Users/busnet/Documents/SpdVpn/androidApp/src/main/java/io/spdvpn/android/account/AccountApi.kt`,
`AccountModels.kt`, `AccountRepository.kt`, `vpn/PremiumVpnCoordinator.kt`, `XrayConfigPolicy.kt`.
No Android files are modified by this task. Implemented route names and required response fields
match that client's DTOs. Additional observation/sync fields are backward compatible with its
`ignoreUnknownKeys=true`. Existing Free endpoints retain success/data, filters/page/source/config.

All successful JSON: `{"success":true,"data":...}`.
Errors: `{"success":false,"error":{"code":"...","message":"safe message"}}` with appropriate
HTTP status (400/401/403/404/409/429/503). Errors never become a successful empty private catalog.
Global serialization still uses `encodeDefaults=true`, `explicitNulls=false`, `ignoreUnknownKeys=true`.
Optional nulls can be absent. All SPD timestamps are UTC epoch milliseconds.

| Route | Auth / request | Response data |
| --- | --- | --- |
| GET `/v1/product` | Public | serverTimeEpochMillis, plans[], premiumVpnConfigured, optional telegramBotUsername, premiumPurchaseEnabled (default false), optional premiumPurchaseUrl |
| POST `/v1/account/login` | JSON username/password | SessionTokens |
| POST `/v1/account/refresh` | JSON refreshToken | rotated SessionTokens; old access and refresh are invalidated |
| POST `/v1/account/logout` | JSON refreshToken | `{loggedOut:true}`; both token capabilities are revoked |
| GET `/v1/account/me` | Bearer **user** access token | AccountProfile |
| GET `/v1/premium/servers` | Bearer user, current verified entitlement | servers[] and serverTimeEpochMillis |
| GET `/v1/premium/servers/{id}/profile` | Bearer owner, current verified entitlement | server, xrayJson, optional expiresAtEpochMillis |

No account id from query/body selects an owner. Admin cookies are invalid as user tokens;
user tokens cannot authorize admin routes. Password reset invalidates all sessions of that
account. Expired/disabled accounts can login and read me, but cannot obtain Premium profiles.
Access expiry and account expiry are independent. There is no account self-registration endpoint.

## Exact required DTO fields

- Plan: id, title, description; optional durationDays, lifetime, quotaBytes, enabled, published, isAssignable,
  priceMinor, currency, displayOrder, localizedTitles and localizedDescriptions. Public product
  includes only enabled, published, valid definitions, ordered by displayOrder then id. Nullable
  pricing is an ISO-4217 minor-unit amount/currency pair; unknown pricing is not free. No payment
  provider or automatic activation is implied. Old documents/clients remain compatible; default
  seeded ids stay MONTH_1, MONTH_3, LIFETIME; administrator-created IDs are also supported.
  `enabled` is active, `published` is public visibility (existing names retained rather than
  introducing duplicate isActive/isPublished flags). `isAssignable` controls only admin assignment.
  No more than three active published valid plans are allowed; private definitions never enter
  product. Android ignores the additive assignment flag and does not impose a hardcoded count.
  See [plans.md](plans.md) for validation, localization,
  request freshness and storage compatibility. Optional external purchase contact is documented
  in [manual-purchase.md](manual-purchase.md); opening it never grants entitlement.
- SessionTokens: accessToken, accessExpiresAtEpochMillis, refreshToken,
  refreshExpiresAtEpochMillis, profile, serverTimeEpochMillis.
- AccountProfile: username, status (ACTIVE/EXPIRED/DISABLED), planId, planTitle,
  optional premiumUntilEpochMillis, lifetime, premiumVpnConfigured. Added: serverTimeEpochMillis,
  syncState, quotaBytes, usedBytes. Status prioritizes disabled, then known time expiry.
- PremiumServerSummary: id (32 lowercase hex), countryCode, countryName, displayName, protocol,
  transport. These describe configurations returned for **this user's** real PasarGuard subscription.
  Unknown country is `countryCode=""`, `countryName="نامشخص"`; no static country/server is invented.
- XrayProfileResponse: server, xrayJson (string containing actual upstream JSON), optional expiry.
  Native TUN and secure TLS are prerequisites; see the [adapter contract](pasarguard-v5.4.1.md).

`premiumVpnConfigured` on product reports transport configuration, not a successful live test.
On account it is conservative: synced, locally eligible, remote active, quota available and
recently observed. On me and both Premium routes, a fresh PasarGuard read is attempted when linked.
Profiles are verified again after subscription download to fence expiry/concurrent admin changes.
Temporary upstream failures return 503 and retain the stored entitlement; they do not disable or
expire the account. Quota exhaustion can leave time status ACTIVE while connection permission is
false. The current Android UI has no separate LIMITED enum; a richer quota/error UI is a later change.

## Admin contract

Browser admin login uses `/admin/api/login` JSON username/password plus exact Origin.
The response sets an HttpOnly cookie and returns username, csrfToken, expiresAtEpochMillis.
GET `/admin/api/session` restores the CSRF token in page memory. All mutations require Origin
and `X-CSRF-Token`; logout revokes the server-side session. No public admin registration.

- GET `/admin/api/dashboard`, `/users?q=&offset=&limit=`, `/users/{id}`, `/users/{id}/operations`, `/plans`, `/audit?offset=&limit=`.
- POST `/users`: username/password/planId; optional existingPasarGuardUserId plus confirmExistingUser.
- POST `/users/{id}/subscription`: action RENEW/CHANGE_PLAN/SET_DISABLED, optional planId/disabled/confirmLifetimeToTimed.
- POST `/users/{id}/password`: new password; revokes the account's app sessions.
- POST `/users/{id}/retry`: explicitly reconcile the **stored** target; no fresh duration arithmetic.
- POST `/users/{id}/refresh`: read actual upstream state without changing it.
- POST `/users/{id}/link`: repair only a failed unconfirmed LINK; pasarGuardUserId/planId/confirm.
- POST `/plans`: full plan definition with a new stable ID; returns 201 with success/data.
- PUT `/plans/{id}`: full plan definition with matching immutable ID; existing entitlements
  remain unchanged. Missing ID returns 404, duplicate creation 409. Omitted isAssignable
  on a legacy PUT preserves the stored value. Publication beyond three eligible definitions
  returns 400 PUBLIC_PLAN_LIMIT. Inactive/non-assignable selections return 400 PLAN_NOT_ASSIGNABLE.
- GET/PUT `/premium-purchase`: `{premiumPurchaseUrl: string|null, premiumPurchaseEnabled: boolean}`;
  protected by the same admin cookie/Origin/CSRF rules. Public product omits the URL when disabled.
- GET `/free`, POST `/free/refresh`: current existing catalog pipeline; busy/cooldown 409, upstream failure 503 with cache retained.
- Existing development panel content routes are served behind this same database-backed admin auth:
  releases/announcements CRUD and recommendation GET/PUT. Public `/v1/app/bootstrap` and presence
  heartbeat/disconnect preserve their prior model. Presence is anonymous/self-reported with TTL 150s,
  not the source of truth for account authorization or billing.

POST users/subscription/link requires `Idempotency-Key` (8–128 safe alphanumeric/underscore/hyphen).
Retain the same key when retrying a request whose outcome is uncertain; different payload with that
key is 409. Create's password is deliberately excluded from the persisted fingerprint; replay of
an already-created account does not reset its password. Use the explicit password endpoint for that.
202 with data.syncState=PENDING acknowledges durable registration **only**. UI must not call it
completed until SYNCED. AccountView returns requested and confirmed separately, operationId,
lastObservedAtEpochMillis and safe error code. No password hash, session tokens or private profile
is present in AccountView, search results, operation history or audit.

## Remaining Android work (not implemented here)

Wire presentation for pending/sync/quota errors and account revalidation to the existing lifecycle;
review token rotation, refresh, revocation and actual engine stop behavior on device. The historical
SPD-PNL-000 offline/time enforcement contract requires a separate Android/service implementation
review: this backend alone cannot enforce a timer inside a running Android VPN process. Free stays
public, while Premium API authorization and the PasarGuard account govern Premium access.
Do not send SPD login passwords to Xray/OpenVPN/SoftEther or send PasarGuard admin credentials to Android.
