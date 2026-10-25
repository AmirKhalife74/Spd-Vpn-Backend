# Approval-gated logical-v2 rollout / rollback

**Instructions only. None of these mutating commands was executed by the identity audit.**
Obtain explicit deployment approval first. These commands target the deployment inspected on
2026-10-24; re-inspect before use and stop if its image, Compose files, volume, schema or artifact
has changed. Do not use the prober/Caddy service, `compose down`, prune, or delete any backup.

The existing SSH config supplies username/port/key through alias `spdvpn`. Do not copy secrets
into shell arguments or print `docker inspect`/resolved Compose environment to the terminal.
The known healthy liveness endpoint is `/health/live`, **not `/health`**.

## 1. Verify the reviewed local artifact

Run on the Mac, only after reviewing all current backend source changes. The working tree is
already dirty; the artifact includes the current source, not just a two-line patch.

```sh
cd /Users/busnet/Downloads/SPD-VPN-Backend-v0.1.0
git diff --check
shasum -a 256 build/libs/spd-vpn-backend-all.jar
# Audited candidate:
# 7a9b8cd117b7b913fe598333119915de68ed53adaf8e0477fb18429c29604f77
unzip -p build/libs/spd-vpn-backend-all.jar \
  app/spdvpn/backend/premium/HttpPasarGuard.class | strings | \
  rg 'logical-v2|endpoint-v2'
```

Rebuilding changes the artifact under review; if the hash differs, verify/test the new artifact
and record its hash instead of bypassing the check. Confirm persistent formats remain compatible:
the audited `001_premium.sql` is byte-identical to the running JAR, and this identity correction
does not introduce a catalog/health snapshot migration. If any additional source change requires
a data migration, **stop**: obtain a consistent stopped-service/SQLite-aware data backup and a
specific data-restoration plan before proceeding. Do not copy a live SQLite file with ordinary cp.

## 2. Recheck production, reserve unique artifacts, retain rollback

The following blocks share the same shell variable on the Mac. Keep its printed value in the
operator's private deployment record; it is a nonsecret release identifier.

```sh
SPD_IDENTITY_STAMP=$(date -u +%Y%m%dT%H%M%SZ)
printf '%s\n' "$SPD_IDENTITY_STAMP"

ssh -o BatchMode=yes spdvpn bash -s -- "$SPD_IDENTITY_STAMP" <<'REMOTE'
set -euo pipefail
umask 077
stamp=$1
[[ "$stamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]]
old=/opt/spdvpn/releases/premium-tun-20261022
backup=/opt/spdvpn/rollbacks/premium-identity-$stamp
release=/opt/spdvpn/releases/premium-identity-$stamp
expected=sha256:50b29ccdd7d4d67c73d92c20d9fc926b75bad916f295118f48e91312776c4d4d
test "$(docker inspect -f '{{.Image}}' spdvpn-api-1)" = "$expected"
test "$(docker inspect -f '{{.State.Health.Status}}' spdvpn-api-1)" = healthy
test -f "$old/.env"
docker compose -p spdvpn --env-file "$old/.env" \
  -f "$old/compose.yaml" -f "$old/compose.premium.yaml" config --quiet
curl --fail --silent --max-time 10 http://127.0.0.1:8080/health/live >/dev/null
df -h /opt/spdvpn
test ! -e "$backup"
test ! -e "$release"
if [ ! -d /opt/spdvpn/rollbacks ]; then
  mkdir -m 700 /opt/spdvpn/rollbacks
fi
mkdir -m 700 "$backup" "$release"
cp -a -- "$old/compose.yaml" "$old/compose.premium.yaml" "$old/.env" "$backup/"
cp -a -- "$old/Dockerfile.deploy" "$old/spd-vpn-backend-all.jar" "$backup/"
docker inspect spdvpn-api-1 > "$backup/container-private.json"
chmod 600 "$backup/container-private.json"
(cd "$backup" && sha256sum compose.yaml compose.premium.yaml .env \
  Dockerfile.deploy spd-vpn-backend-all.jar > checksums-private.txt)
docker image tag "$expected" "spdvpn/backend:rollback-premium-identity-$stamp"
printf '%s\n' "$expected" > "$backup/image-id.txt"
printf 'Backup: %s\nRelease: %s\n' "$backup" "$release"
REMOTE
```

Read the disk-space result and stop if insufficient. `container-private.json`, copied `.env`
and their checksum manifest are private rollback artifacts; do not print, upload or commit them.
No volume, secret value, existing config or old image is modified by this preparation.

## 3. Transfer only the JAR and build while the old API remains running

```sh
scp build/libs/spd-vpn-backend-all.jar \
  "spdvpn:/opt/spdvpn/releases/premium-identity-$SPD_IDENTITY_STAMP/spd-vpn-backend-all.jar"

ssh -o BatchMode=yes spdvpn bash -s -- "$SPD_IDENTITY_STAMP" <<'REMOTE'
set -euo pipefail
umask 077
stamp=$1
[[ "$stamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]]
release=/opt/spdvpn/releases/premium-identity-$stamp
test "$(sha256sum "$release/spd-vpn-backend-all.jar" | cut -d' ' -f1)" = \
  7a9b8cd117b7b913fe598333119915de68ed53adaf8e0477fb18429c29604f77
new=spdvpn/backend:premium-logical-v2-$stamp
rollback=spdvpn/backend:rollback-premium-identity-$stamp
# Inherit the exact currently running runtime image; replace only the reviewed fat JAR.
printf 'FROM %s\nCOPY --chown=spdvpn:spdvpn spd-vpn-backend-all.jar /app/app.jar\n' \
  "$rollback" | docker build -f - -t "$new" "$release"
printf 'services:\n  api:\n    image: %s\n' "$new" > "$release/compose.identity.yaml"
printf 'services:\n  api:\n    image: %s\n' "$rollback" > "$release/compose.rollback.yaml"
docker image inspect -f '{{.Id}}' "$new" > "$release/image-id.txt"
REMOTE
```

This deliberately avoids the old deployment's unusable default Dockerfile build path: that
release contains `Dockerfile.deploy` and a prebuilt JAR, not a complete Gradle source context.
No `.git`, local `.env`, data directory, cache or Android artifact is transferred.

## 4. Switch only API, bounded health check; automatically restore image on failure

```sh
ssh -o BatchMode=yes spdvpn bash -s -- "$SPD_IDENTITY_STAMP" <<'REMOTE'
set -euo pipefail
stamp=$1
[[ "$stamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]]
old=/opt/spdvpn/releases/premium-tun-20261022
release=/opt/spdvpn/releases/premium-identity-$stamp
compose=(docker compose -p spdvpn --env-file "$old/.env" \
  -f "$old/compose.yaml" -f "$old/compose.premium.yaml")
"${compose[@]}" -f "$release/compose.identity.yaml" config --quiet
wait_healthy() {
  local expected=$1
  for attempt in $(seq 1 60); do
    if [ "$(docker inspect -f '{{.Image}}' spdvpn-api-1)" = "$expected" ] && \
       [ "$(docker inspect -f '{{.State.Health.Status}}' spdvpn-api-1)" = healthy ] && \
       curl --fail --silent --max-time 2 http://127.0.0.1:8080/health/live >/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}
rollback_api() {
  "${compose[@]}" -f "$release/compose.rollback.yaml" config --quiet
  "${compose[@]}" -f "$release/compose.rollback.yaml" \
    up -d --no-deps --no-build --pull never --force-recreate api
  wait_healthy sha256:50b29ccdd7d4d67c73d92c20d9fc926b75bad916f295118f48e91312776c4d4d
}
if ! "${compose[@]}" -f "$release/compose.identity.yaml" \
  up -d --no-deps --no-build --pull never --force-recreate api; then
  rollback_api
  exit 1
fi
if ! wait_healthy "$(< "$release/image-id.txt")"; then
  rollback_api
  echo 'Candidate unhealthy; previous image restored and health verified.' >&2
  exit 1
fi
docker inspect -f '{{.Image}} {{.State.Health.Status}}' spdvpn-api-1
REMOTE
```

The health loop is bounded (60 attempts; each curl at most 2 seconds plus 2-second pause).
Health alone is insufficient: before accepting the release, repeat the hidden-input same-account
catalog/profile audit. Require stable named IDs across catalog reads and current-profile lookup;
inspect only bounded `premiumProfile` PARSED/CATALOG/LOOKUP diagnostic lines and codes. The new
PARSED line must say `identityPolicy=logical-v2`. Do not dump all container logs/private responses.
Refresh/reselect in Android once after changing the identity policy. Do not auto-connect.

If catalog/profile validation fails, execute rollback below immediately and stop investigating
in production. Named aliases removed/renamed/duplicated by upstream are still supposed to fail
closed; assess those separately from an implementation failure.

## 5. Explicit rollback (same shell release identifier)

```sh
ssh -o BatchMode=yes spdvpn bash -s -- "$SPD_IDENTITY_STAMP" <<'REMOTE'
set -euo pipefail
stamp=$1
[[ "$stamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]]
old=/opt/spdvpn/releases/premium-tun-20261022
release=/opt/spdvpn/releases/premium-identity-$stamp
compose=(docker compose -p spdvpn --env-file "$old/.env" \
  -f "$old/compose.yaml" -f "$old/compose.premium.yaml" \
  -f "$release/compose.rollback.yaml")
"${compose[@]}" config --quiet
"${compose[@]}" up -d --no-deps --no-build --pull never --force-recreate api
ready=false
for attempt in $(seq 1 60); do
  if [ "$(docker inspect -f '{{.State.Health.Status}}' spdvpn-api-1)" = healthy ] && \
     curl --fail --silent --max-time 2 http://127.0.0.1:8080/health/live >/dev/null; then
    ready=true; break
  fi
  sleep 2
done
test "$ready" = true
test "$(docker inspect -f '{{.Image}}' spdvpn-api-1)" = \
  sha256:50b29ccdd7d4d67c73d92c20d9fc926b75bad916f295118f48e91312776c4d4d
docker inspect -f '{{.Image}} {{.State.Health.Status}}' spdvpn-api-1
REMOTE
```

Original Compose/env files are reused unchanged, so there is normally no config restoration to
perform. Persistent data is never removed/replaced by these commands. This image-only rollback
is valid only under the no-new-persistent-schema precondition above; never roll a binary back
across a data migration without a separate consistent data backup/restoration procedure.
Keep the rollback image and restricted backup directory after success. Record the actual new
image ID, health, JAR hash, current Compose override path and sanitized API results. No Git push,
Caddy/firewall/SSH change or unrelated service recreation is part of this procedure.
