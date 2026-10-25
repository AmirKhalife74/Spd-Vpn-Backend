# Verified PasarGuard adapter contract

Target: **v5.4.1**, commit `b56ffe369f542152c52c69733205baeaf3f6e4cd`.
Inspected on 2026-10-21 from the official source, fetched into a temporary reference folder.
No PasarGuard implementation is embedded or copied into the SPD runtime.

Primary source references:

- [admin token route](https://github.com/PasarGuard/panel/blob/b56ffe369f542152c52c69733205baeaf3f6e4cd/app/routers/admin.py)
- [user routes](https://github.com/PasarGuard/panel/blob/b56ffe369f542152c52c69733205baeaf3f6e4cd/app/routers/user.py)
- [user schemas](https://github.com/PasarGuard/panel/blob/b56ffe369f542152c52c69733205baeaf3f6e4cd/app/models/user.py)
- [subscription operation](https://github.com/PasarGuard/panel/blob/b56ffe369f542152c52c69733205baeaf3f6e4cd/app/operation/subscription.py)
- [subscription formats](https://github.com/PasarGuard/panel/blob/b56ffe369f542152c52c69733205baeaf3f6e4cd/app/models/settings.py)

| Operation | Verified upstream request | Relevant response |
| --- | --- | --- |
| Admin token | POST `/api/admin/token`, form-urlencoded username/password | plain object `access_token`, `token_type` |
| Locate deterministic creation identity | GET `/api/user/by-username/{username}` | `UserResponse` |
| Create VPN user | POST `/api/user` | `UserResponse`, numeric `id` |
| Read stable identity | GET `/api/user/by-id/{id}` | `UserResponse` |
| Apply absolute target | PUT `/api/user/by-id/{id}` | `UserResponse` |
| Read actual Xray profiles | GET `/api/user/{id}/subscription/xray` | JSON subscription configuration(s) |

The upstream does **not** use SPD's success/data wrapper. `UserResponse` includes a numeric
id, immutable username, status, expire, data_limit, used_traffic and note. SPD deliberately
discards subscription URLs, admin metadata and proxy_settings when persisting an observation.
Expiry responses may be UTC ISO datetime or Unix seconds; SPD converts them to epoch ms.
Writes send UTC ISO datetime. `expire=0` clears expiry; `data_limit=0` means unlimited.
On a modify request null/omitted fields mean unchanged, so clearing must use 0 explicitly.
Create sets `data_limit_reset_strategy=no_reset` and configured existing `group_ids`.
SPD does not manage nodes, inbounds, groups, or Xray infrastructure configuration.

Use a dedicated PasarGuard administrator with the minimum permissions for user create/read/
update and group access. Credentials stay server-side. HTTPS and valid certificate checking
are mandatory except for explicit loopback HTTP in tests. Redirects are disabled, bodies
are bounded to 2 MiB, and timeouts are bounded. Upstream errors/bodies never reach logs or API.
An expired upstream token is refreshed once after 401; there is no trust-all or mock mode.

## Exactly-once effect, without a distributed transaction

SPD writes an account and durable operation before networking. A CREATE uses a deterministic
`spd_` username plus an installation/account marker in note. An existing username can only
be adopted after marker validation; an ambiguous create response is resolved by reading it.
After association the numeric id is authoritative and unique across SPD accounts. Mismatched
id/name is a conflict. An explicit LINK reads the chosen id, verifies it, and adopts its actual
expiry/quota/disabled state without changing the upstream account. It never extends a linked
user just for attaching it. Lifetime/timed mismatch is rejected; a failed, unconfirmed LINK
can be corrected explicitly in the panel. Existing confirmed links cannot be silently moved.

Operations store an **absolute** desired expiry, total quota ceiling and disabled flag.
Retry GETs the remote state before PUT; if it already matches, no PUT occurs. A timeout or
restart after a write therefore cannot add days twice. A final GET verifies the actual result.
No usage reset endpoint is called. Renewal replaces the total quota ceiling from the selected
plan, preserving reported usage. To grant additional usable traffic choose a larger ceiling;
this release does not implement periodic resets or traffic accounting.

PENDING, SYNCED, FAILED and non-sensitive error codes are durable. Failed operations require
an explicit retry; pending operations resume after restart (15-second sweep, one worker).
A read detects drift and displays requested and confirmed separately. It does not overwrite
PasarGuard without an explicit reconciliation request. Retrying reconciliation reapplies the
stored target; confirm it in the panel. Network errors do not manufacture expiry/disabled.
This first version supports one SPD API instance against its SQLite database, not HA.

## Android profile prerequisite

The inspected Android `XrayConfigPolicy` requires a native `tun` inbound and rejects
`allowInsecure=true`. Configure the actual PasarGuard Xray subscription template for that
Android engine. Default SOCKS/HTTP-only templates are not silently rewritten into guessed
TUN settings. Only real compatible configurations are listed; unsupported templates yield
an empty catalog. No static Premium servers or synthetic VPN configuration is substituted.
Profile ids derive from non-credential endpoint/protocol/transport/remark metadata. Duplicate
ambiguous ids fail closed. Country metadata unavailable upstream is explicitly unknown,
not inferred from flags or hostnames. Full profiles are returned only to their authenticated
owner after a current entitlement read, held in memory and never stored in SPD's database.

Live authorized PasarGuard provisioning/renewal: **NOT_RUN**. No test instance or credentials
were supplied. Controlled HTTP contract tests and failure/recovery tests cover this adapter;
they do not certify a deployed PasarGuard's groups, permissions, templates, or network reachability.
