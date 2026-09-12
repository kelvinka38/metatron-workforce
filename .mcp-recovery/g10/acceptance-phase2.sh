#!/usr/bin/env bash
# Isolated recovery acceptance phase 2: refresh rotation, concurrency, revocation and restart.
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
  docker run -d --name "$c" --restart no --memory 512m --memory-swap 768m --pids-limit 256 --network metatron-gateway-online --add-host host.docker.internal:host-gateway -e METATRON_PROXY_ASSERTION="$ASSERTION" -v /opt/metatron/ssh-mcp/id_ed25519:/ssh/id_ed25519:ro metatron-ssh-mcp-runtime:g10-acceptance >/dev/null
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
BOOT="$RUNNER_TEMP/g10-bootstrap-2.json"
docker exec "$A" node /tmp/acceptance-client.mjs bootstrap > "$BOOT"
CLIENT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["clientId"])' "$BOOT")"
REDIRECT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["redirectUri"])' "$BOOT")"
ACCESS1="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["accessToken"])' "$BOOT")"
REFRESH1="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["refreshToken"])' "$BOOT")"
ADMIN="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["adminCookie"])' "$BOOT")"
CSRF="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["csrf"])' "$BOOT")"
check_ok() {
  c="$1"; token="$2"; out="$RUNNER_TEMP/mcp-ok-${c}.json"
  docker exec "$c" node /tmp/acceptance-client.mjs mcp "$token" tools/list > "$out"
  python3 -c 'import json,sys; x=json.load(open(sys.argv[1])); assert x["status"]==200 and x["ok"] and x["hasServerStatus"]' "$out"
}
check_rejected() {
  c="$1"; token="$2"; out="$RUNNER_TEMP/mcp-reject-${c}.json"
  docker exec "$c" node /tmp/acceptance-client.mjs mcp "$token" tools/list > "$out"
  python3 -c 'import json,sys; x=json.load(open(sys.argv[1])); assert x["status"]==401 and not x["ok"]' "$out"
}
check_ok "$A" "$ACCESS1"; check_ok "$B" "$ACCESS1"
R1="$RUNNER_TEMP/refresh-rotate.json"
docker exec "$A" node /tmp/acceptance-client.mjs refresh "$REFRESH1" "$CLIENT" "$REDIRECT" > "$R1"
python3 -c 'import json,sys; x=json.load(open(sys.argv[1])); assert x["status"]==200 and x["body"].get("access_token") and x["body"].get("refresh_token")' "$R1"
REFRESH2="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["body"]["refresh_token"])' "$R1")"
REPLAY="$RUNNER_TEMP/refresh-replay.json"
docker exec "$B" node /tmp/acceptance-client.mjs refresh "$REFRESH1" "$CLIENT" "$REDIRECT" > "$REPLAY"
python3 -c 'import json,sys; x=json.load(open(sys.argv[1])); assert x["status"]==400 and x["body"].get("error")=="invalid_grant"' "$REPLAY"
echo 'G10_REFRESH_ROTATION_REPLAY_REJECT_PASS'
CA="$RUNNER_TEMP/refresh-concurrent-a.json"; CB="$RUNNER_TEMP/refresh-concurrent-b.json"
docker exec "$A" node /tmp/acceptance-client.mjs refresh "$REFRESH2" "$CLIENT" "$REDIRECT" > "$CA" & PA=$!
docker exec "$B" node /tmp/acceptance-client.mjs refresh "$REFRESH2" "$CLIENT" "$REDIRECT" > "$CB" & PB=$!
wait "$PA"; wait "$PB"
WIN="$RUNNER_TEMP/refresh-winner.txt"
python3 -c 'import json,sys; xs=[json.load(open(p)) for p in sys.argv[1:]]; assert sorted(x["status"] for x in xs)==[200,400],xs; loser=[x for x in xs if x["status"]==400][0]; assert loser["body"].get("error")=="invalid_grant"; winner=[x for x in xs if x["status"]==200][0]["body"]; assert winner.get("access_token") and winner.get("refresh_token"); print(winner["access_token"]); print(winner["refresh_token"])' "$CA" "$CB" > "$WIN"
ACCESS3="$(sed -n '1p' "$WIN")"; REFRESH3="$(sed -n '2p' "$WIN")"
check_ok "$A" "$ACCESS3"; check_ok "$B" "$ACCESS3"
echo 'G10_CONCURRENT_REFRESH_AT_MOST_ONE_WINNER_PASS'
REV="$RUNNER_TEMP/revoke.json"
docker exec "$A" node /tmp/acceptance-client.mjs revoke "$ADMIN" "$CSRF" "$CLIENT" > "$REV"
python3 -c 'import json,sys; x=json.load(open(sys.argv[1])); assert x["status"]==200' "$REV"
sleep 1
check_rejected "$B" "$ACCESS3"
echo 'G10_REVOKE_A_REJECT_B_WITHOUT_RESTART_PASS'
docker restart "$B" >/dev/null
ok=0
for i in $(seq 1 60); do
  [ "$(docker inspect "$B" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>/dev/null || true)" = healthy ] && { ok=1; break; }
  sleep 1
done
[ "$ok" = 1 ]
check_rejected "$B" "$ACCESS3"
echo 'G10_RESTARTED_REPLICA_LOADS_REVOCATION_PASS'
POSTREV="$RUNNER_TEMP/refresh-post-revoke.json"
docker exec "$B" node /tmp/acceptance-client.mjs refresh "$REFRESH3" "$CLIENT" "$REDIRECT" > "$POSTREV"
python3 -c 'import json,sys; x=json.load(open(sys.argv[1])); assert x["status"]==400 and x["body"].get("error")=="invalid_grant"' "$POSTREV"
echo 'G10_REFRESH_REVOCATION_CROSS_REPLICA_PASS'
for c in "$A" "$B"; do
  logs="$RUNNER_TEMP/logs-$c.txt"; docker logs "$c" > "$logs" 2>&1
  for t in "$ACCESS1" "$REFRESH1" "$REFRESH2" "$ACCESS3" "$REFRESH3"; do
    ! grep -Fq "$t" "$logs"
  done
done
echo 'G10_NO_RAW_TOKEN_LOGS_PASS'
cleanup
trap - EXIT
[ "$(sha256sum /usr/local/sbin/metatron-mcp-broker | awk '{print $1}')" = "$PROD_SHA" ]
[ ! -e /var/lib/metatron-mcp/g10-acceptance-auth-state.enc ]
for c in metatron-mcp-runtime-g9 metatron-mcp-runtime-g9-standby; do
  [ "$(docker inspect "$c" --format '{{.State.Status}}/{{.State.Health.Status}}')" = 'running/healthy' ]
done
[ "$(curl -sS -o /dev/null -w '%{http_code}' --max-time 8 https://ssh.metatron.vn/mcp)" = 401 ]
echo 'G10_PHASE2_FULL_CROSS_REPLICA_ACCEPTANCE_PASS'
