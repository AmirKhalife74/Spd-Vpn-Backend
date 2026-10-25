# Manual Premium purchase contact

The existing plan catalog, admin/user authentication, subscription expiry/quotas and VPN
authorization are unchanged. No order, payment transaction, Telegram bot, automatic activation
or extra entitlement exists. Selection/opening a URL is not proof of purchase.

## Admin / persistence

In **تنظیم پلن‌ها → تماس برای خرید اشتراک**, set the administrator's real contact URL and enable
the action. No real contact username is seeded or compiled into Android.

- `GET /admin/api/premium-purchase`: current settings, admin cookie required.
- `PUT /admin/api/premium-purchase`: same-origin admin cookie plus `X-CSRF-Token`, JSON body:

```json
{"premiumPurchaseUrl":"https://t.me/example_admin","premiumPurchaseEnabled":true}
```

This is a documentation/test example, not a configured production contact.
Initial server policy accepts only direct HTTPS `t.me/<username>` contact/bot links: a letter
followed by 4–31 letters/digits/underscores. Surrounding whitespace is trimmed, scheme/host and
default HTTPS port normalized. No userinfo, other hosts, custom ports, query, fragment, encoded
path, invite/post/proxy/share route is accepted. Invalid settings return structured 400 without
echoing input; enabled=true requires a valid URL. Validation checks shape, not ownership or
existence of the Telegram account; the administrator must verify the destination.

Disabling may retain the admin's stored URL. Sending null while disabled clears it. Storage is
the existing `PANEL_CONTENT_PATH` JSON file, via its Mutex/IO dispatcher/atomic replacement.
The optional `premiumPurchase` settings object defaults to null URL/disabled for old schema-1
snapshots; schema and other content are unchanged. Include this file in existing backups/volumes.
No database migration or new environment secret is needed. Multiple backend replicas still need
the existing store's single-writer architecture; no new distributed configuration mechanism.

## Public product / Android

The existing public, no-store `GET /v1/product` adds:

```json
{
  "success": true,
  "data": {
    "serverTimeEpochMillis": 1792843200000,
    "plans": [],
    "premiumVpnConfigured": false,
    "premiumPurchaseEnabled": true,
    "premiumPurchaseUrl": "https://t.me/example_admin"
  }
}
```

Old clients ignore these additions; new clients default missing fields to disabled/null. When
disabled/invalid, enabled=false and the nullable URL is omitted (`explicitNulls=false`). No admin
cookie/token, account details or internal configuration enters the product response.

Android uses the existing ProductResponse/AccountRepository refresh path and 60-second freshness,
not a second request/repository. Explicit refresh picks up link changes/disablement immediately;
fresh cached settings can remain until the next refresh. This is contact availability, never an
authorization mechanism. During fetch failure/loading, or without a selectable published plan,
the contact button is not offered. The last catalog/timestamp and existing retry behavior remain.

The button says **Contact to Purchase / درخواست خرید اشتراک** and uses ACTION_VIEW, first targeting
Telegram for t.me, then the platform external browser/app resolver. ActivityNotFoundException and
SecurityException produce a localized failure message, never a crash. No login credentials, user
ID, subscription URL, plan ID or query parameter is appended; discuss the plan manually. The URL
is not logged. No entitlement API is called. Existing account holders still sign in normally.

The Android boundary accepts only absolute HTTPS URLs with a host, no userinfo/control/whitespace
and only the default/443 port. It intentionally does not hardcode Telegram as the only destination:
future approved checkout hosts can be allowed in the **server-owned** URL policy without a new APK.
Current admin validation rejects other providers. A future provider still needs explicit backend
allowlist/validation and verified payment/activation implementation; changing a URL alone does not
create a checkout or trust a payment result.

## Release checklist

1. Review and deploy backend and Android separately with normal rollback approval; not deployed here.
2. Confirm `PANEL_CONTENT_PATH` remains persistent and admin-cookie/Origin/CSRF protection is intact.
3. Verify ownership of the actual contact account, enter its URL in admin, then enable explicitly.
4. Refresh Android Premium; check en/fa, light/dark, Telegram/browser handling and disabled state.
5. Continue existing manual account issuance/subscription administration; opening contact grants nothing.
6. Do not label manual contact as an integrated/verified payment provider.
