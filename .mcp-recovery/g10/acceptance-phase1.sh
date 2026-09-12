#!/usr/bin/env bash
# Isolated recovery acceptance only. Uses a temporary broker state file and ephemeral assertion.
set -euo pipefail
A=metatron-mcp-runtime-g10-acceptance-a
B=metatron-mcp-runtime-g10-acceptance-b
PROD_SHA='c5a79966d6096a050d0799e594c78f501007071a62f5dc0138e2fe8f45a1558e'

cleanup() {
  set +e
  docker rm -f "$A" "$B" >/dev/null 2>&1 || true
  docker run --rm --entrypoint /bin/sh -v /usr/local/sbin:/host-sbin -v "$PWD/.mcp-recovery/g10:/candidate:ro" metatron-ssh-mcp-runtime:g9 -c 'cp /candidate/metatron-mcp-broker.g10 /host-sbin/.metatron-mcp-broker.restore; chown 0:0 /host-sbin/.metatron-mcp-broker.restore; chmod 0755 /host-sbin/.metatron-mcp-broker.restore; mv -f /host-sbin/.metatron-mcp-broker.restore /host-sbin/metatron-mcp-broker' >/dev/null 2>&1 || true
  docker run --rm --entrypoint /bin/sh -v /var/lib:/host-var-lib -v /var/lock:/host-var-lock metatron-ssh-mcp-runtime:g9 -c 'rm -f /host-var-lib/metatron-mcp/g10-acceptance-auth-state.enc /host-var-lock/metatron-mcp-auth-state.g10-acceptance.lock; rmdir /host-var-lib/metatron-mcp 2>/dev/null || true' >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

[ "$(sha256sum /usr/local/sbin/metatron-mcp-broker | awk '{print $1}')" = "$PROD_SHA" ]
docker run --rm --entrypoint /bin/sh -v /usr/local/sbin:/host-sbin -v "$PWD/.mcp-recovery/g10:/candidate:ro" metatron-ssh-mcp-runtime:g9 -c 'cp /candidate/metatron-mcp-broker.g10-test /host-sbin/.metatron-mcp-broker.test; chown 0:0 /host-sbin/.metatron-mcp-broker.test; chmod 0755 /host-sbin/.metatron-mcp-broker.test; mv -f /host-sbin/.metatron-mcp-broker.test /host-sbin/metatron-mcp-broker'

ASSERTION="$(node -e "process.stdout.write(require('crypto').randomBytes(32).toString('hex'))")"
for c in "$A" "$B"; do
  docker run -d --name "$c" --restart no --memory 512m --memory-swap 768m --pids-limit 256 \
    --network metatron-gateway-online --add-host host.docker.internal:host-gateway \
    -e METATRON_PROXY_ASSERTION="$ASSERTION" \
    -v /opt/metatron/ssh-mcp/id_ed25519:/ssh/id_ed25519:ro \
    metatron-ssh-mcp-runtime:g10-acceptance >/dev/null
done
for c in "$A" "$B"; do
  ok=0
  for i in $(seq 1 60); do
    [ "$(docker inspect "$c" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>/dev/null || true)" = healthy ] && { ok=1; break; }
    sleep 1
  done
  [ "$ok" = 1 ]
  docker cp .mcp-recovery/g10/acceptance-client.mjs "$c:/tmp/acceptance-client.mjs"
done

META="$RUNNER_TEMP/g10-meta.json"; UNAUTH="$RUNNER_TEMP/g10-unauth.json"; BOOT="$RUNNER_TEMP/g10-bootstrap.json"
docker exec "$A" node /tmp/acceptance-client.mjs metadata > "$META"
python3 -c 'import json,sys; x=json.load(open(sys.argv[1])); assert x["authStatus"]==200 and x["protectedStatus"]==200 and x["refresh"] and x["authCode"] and x["pkce"] and x["dcr"]' "$META"
docker exec "$A" node /tmp/acceptance-client.mjs unauth > "$UNAUTH"
python3 -c 'import json,sys; x=json.load(open(sys.argv[1])); assert x["status"]==401 and "resource_metadata=" in x["wwwAuthenticate"]' "$UNAUTH"
docker exec "$A" node /tmp/acceptance-client.mjs bootstrap > "$BOOT"
python3 -c 'import json,sys; x=json.load(open(sys.argv[1])); assert x["authorizationUi"] and x["accessToken"] and x["refreshToken"]' "$BOOT"
ACCESS="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["accessToken"])' "$BOOT")"
for c in "$A" "$B"; do
  out="$RUNNER_TEMP/mcp-$c.json"
  docker exec "$c" node /tmp/acceptance-client.mjs mcp "$ACCESS" tools/list > "$out"
  python3 -c 'import json,sys; x=json.load(open(sys.argv[1])); assert x["status"]==200 and x["ok"] and x["hasServerStatus"]' "$out"
done

echo 'G10_PHASE1_METADATA_DCR_AUTH_UI_PKCE_ACCESS_A_B_PASS'
cleanup
trap - EXIT
[ "$(sha256sum /usr/local/sbin/metatron-mcp-broker | awk '{print $1}')" = "$PROD_SHA" ]
[ ! -e /var/lib/metatron-mcp/g10-acceptance-auth-state.enc ]
echo 'G10_PHASE1_CLEANUP_PASS'
