#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# The four numbers that decide whether an AOT build was worth building: image size,
# two startup times, and resident memory at three points in a process's life.
#
# It exists as a file and not as a paragraph in a runbook because it is run at least
# three times — once for the JVM image, once with the AOT cache, once native — and a
# comparison whose two halves were measured by hand is not a comparison.
#
# It is not called by `smoke.sh` and is not meant to be. `smoke.sh` prints one row as a
# regression guard and is over in a couple of minutes; this one sleeps 60 s per round and
# answers a different question, which is whether one image is better than another. Run it
# by hand, against a stack that is already up.
#
# Usage:  docker compose -f docker-compose.yml -f backend/smoke/docker-compose.smoke.yml \
#           -p leadgen-smoke up -d postgres
#         IMAGE=leadgen-api:baseline backend/smoke/measure.sh [label]
#
# Requires a reachable Postgres. MEASURE_NETWORK and the POSTGRES_* variables say
# where; the defaults match the stack `smoke.sh` brings up.
set -euo pipefail

IMAGE="${IMAGE:?set IMAGE to the image under test}"
LABEL="${1:-$IMAGE}"
# The network of the compose project `smoke.sh` uses. Compose names it after the
# project, so this default is only right while that project is `leadgen-smoke`.
NETWORK="${MEASURE_NETWORK:-leadgen-smoke_default}"
PGHOST="${MEASURE_POSTGRES_HOST:-postgres}"
PORT="${MEASURE_PORT:-18080}"
# Five, because two were measured not to be enough: the spread on a laptop is ±24 % around
# the median, wider than the difference an AOT step is expected to make, so a single
# before-and-after pair can show an improvement, no change or a regression depending on
# which two runs it happened to take. Round 1 still migrates 27 files into an empty database
# and the rest do not, which is the cold-versus-warm distinction — but that gap turned out to
# sit below the noise, so it is a thing the script records rather than a thing it proves.
ROUNDS="${MEASURE_ROUNDS:-5}"

say() { printf '%s\n' "$*" >&2; }

# Compressed size per platform if the image is in a registry, on-disk size otherwise.
# Both are quoted, because "35 MB" means something different in a `docker pull` and in
# a disk quota and the two are routinely confused.
image_size() {
  local disk manifest
  disk=$(docker image inspect --format '{{.Size}}' "$IMAGE" 2>/dev/null || echo 0)
  manifest=$(docker buildx imagetools inspect --raw "$IMAGE" 2>/dev/null \
    | jq -r '[(.manifests // [])[] | "\(.platform.os)/\(.platform.architecture):\(.size)"] | join(" ")' 2>/dev/null || true)
  printf '%s|%s' "$disk" "${manifest:-local only}"
}

# VmRSS of PID 1 and not `docker stats`: the cgroup number includes page cache and
# over-reports a JVM by 50-150 MB. The native runtime image has no shell, so this
# reads /proc through the host's view of the container instead of `docker exec sh`.
rss_kb() {
  local cid=$1 pid
  pid=$(docker inspect --format '{{.State.Pid}}' "$cid")
  if [[ "$pid" != "0" ]] && [[ -r "/proc/$pid/status" ]]; then
    awk '/^VmRSS:/ {print $2}' "/proc/$pid/status"
    return
  fi
  # Docker Desktop keeps the container's /proc inside its own VM, so the host cannot
  # read it. cgroup memory.current is the next-best single number that is the same
  # metric for both images — it includes page cache, so it is comparable to itself and
  # not to a VmRSS taken on Linux. Which one was used is printed with the row.
  docker exec "$cid" sh -c 'cat /sys/fs/cgroup/memory.current 2>/dev/null' 2>/dev/null \
    | awk '{printf "%d", $1/1024}' \
    || docker stats --no-stream --format '{{.MemUsage}}' "$cid" | awk '{print $1}'
}

run_once() {
  local round=$1 cid started healthy t0 boot_log
  t0=$(python3 -c 'import time; print(int(time.time()*1000))')
  # The same five directory variables `docker-compose.yml` sets, and for the same reason:
  # their defaults resolve against /app, which is root-owned while the process is not.
  # Leave MANUAL_INBOX_DIR out and the context fails at ApplicationReadyEvent with
  # `AccessDeniedException: /app/config` — after Tomcat has bound, so the log reads
  # "Started ... in 3.3 seconds" one line above the crash. tmpfs, so a measurement writes
  # nothing to the host and two runs cannot see each other's state.
  cid=$(docker run -d --network "$NETWORK" -p "$PORT:8080" \
        --tmpfs /packages:uid=100,gid=101 --tmpfs /inbox:uid=100,gid=101 \
        -e POSTGRES_HOST="$PGHOST" -e POSTGRES_PORT=5432 \
        -e POSTGRES_DB="${POSTGRES_DB:-leadgen}" \
        -e POSTGRES_USER="${POSTGRES_USER:-leadgen}" \
        -e POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-leadgen}" \
        -e LEADGEN_CONFIG_DIR="${MEASURE_CONFIG_DIR:-/config}" \
        -e PACKAGES_DIR=/packages -e DIGEST_DIR=/packages/digest \
        -e INBOX_DIR=/inbox -e MANUAL_INBOX_DIR=/inbox \
        -e SERVER_ADDRESS=0.0.0.0 \
        ${MEASURE_CONFIG_MOUNT:+-v "$MEASURE_CONFIG_MOUNT:/config:ro"} \
        "$IMAGE")
  trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' RETURN

  local deadline=$(( SECONDS + 120 ))
  until curl -sf "http://localhost:$PORT/actuator/health" >/dev/null 2>&1; do
    if (( SECONDS > deadline )); then
      say "FAILED: not healthy within 120 s"
      docker logs "$cid" 2>&1 | tail -40 >&2
      return 1
    fi
    sleep 0.05
  done
  healthy=$(( $(python3 -c 'import time; print(int(time.time()*1000))') - t0 ))

  # Spring's own number, which excludes container and JVM start, so it is always the
  # smaller of the two. The gap between them is the part an AOT cache cannot touch.
  boot_log=$(docker logs "$cid" 2>&1 | sed -n 's/.*Started LeadGenerationApplication in \([0-9.]*\) seconds.*/\1/p' | tail -1)
  started="${boot_log:-?}"

  local rss_hot rss_idle
  rss_hot=$(rss_kb "$cid")
  sleep 60
  rss_idle=$(rss_kb "$cid")

  printf '%s\t%s\t%s\t%s\t%s\n' "$round" "$healthy" "$started" "$rss_hot" "$rss_idle"
  docker rm -f "$cid" >/dev/null 2>&1 || true
}

size=$(image_size)
say "measuring $LABEL ($IMAGE)"
printf '# %s\n' "$LABEL"
printf 'image bytes on disk: %s\n' "${size%%|*}"
printf 'manifest sizes: %s\n' "${size##*|}"
printf 'round\ttime-to-healthy(ms)\tStarted-in(s)\tRSS-hot(kB)\tRSS-idle-60s(kB)\n'
for ((r = 1; r <= ROUNDS; r++)); do
  run_once "$r"
done
