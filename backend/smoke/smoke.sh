#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# What has to work in a finished image, checked against the finished image.
#
# The JUnit suite stays the authority on behaviour; this exists for the failures a JVM test
# cannot see, because they are properties of how the artifact was built rather than of the
# code in it. A native image with a missing reflection hint compiles, starts, and reports
# healthy — and then returns an empty result from one stage. Every step below is one such
# stage, and each one asserts on the result rather than on the absence of a stack trace.
#
#   SMOKE_IMAGE=leadgen-api:aot backend/smoke/smoke.sh
#   SMOKE_IMAGE=leadgen-api-native:local SMOKE_LLM_PROVIDER=anthropic backend/smoke/smoke.sh
#
# Leaves nothing behind: its own compose project, its own volume, torn down on exit.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

IMAGE="${SMOKE_IMAGE:?set SMOKE_IMAGE to the image under test}"
PROJECT="${SMOKE_PROJECT:-leadgen-smoke}"
API="http://localhost:${SMOKE_API_PORT:-18080}"
# The version prefix in one place, because it is not the application's to keep forever and
# a suite that spells it out ten times is ten things to miss on the next bump. `actuator`
# is not under it, so `$API` stays as well.
V1="$API/api/v1"
WIREMOCK="http://localhost:${SMOKE_WIREMOCK_PORT:-18082}"
GREENMAIL="http://localhost:${SMOKE_GREENMAIL_API_PORT:-18081}"
# The rollout window this is allowed to cost. A regression past it is a failure and not a
# shrug; on a Linux runner both images are well inside it.
BOOT_BUDGET_MS="${SMOKE_BOOT_BUDGET_MS:-15000}"
COMPOSE=(docker compose -f docker-compose.yml -f backend/smoke/docker-compose.smoke.yml -p "$PROJECT")

# `postgres` is inherited verbatim from `docker-compose.yml`, and that includes its published
# port. An overlay cannot take a port back — Compose appends port lists rather than replacing
# them — so the host side is moved by the variable the base file already interpolates. Without
# this the suite cannot run while the development stack is up, which is the only time anybody
# would want to run it. The container side stays 5432 and `smoke-api` connects to that.
export POSTGRES_PORT="${SMOKE_POSTGRES_PORT:-15433}"
export SMOKE_IMAGE
export POSTGRES_DB="${POSTGRES_DB:-leadgen}"
export POSTGRES_USER="${POSTGRES_USER:-leadgen}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-leadgen}"

# `docker-compose.yml` declares `.env` as `required: true`, so Compose refuses to start
# without it and says so about a path rather than about this suite. Said here instead, because
# a fresh clone and a CI runner both hit it and the answer is the same one line.
if [[ ! -f .env ]]; then
  printf 'no .env in %s — `cp .env.example .env` first (docker-compose.yml requires it)\n' "$PWD" >&2
  exit 1
fi

step=0
failures=0
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

pass() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; failures=$((failures + 1)); }
section() { step=$((step + 1)); printf '\n%d. %s\n' "$step" "$1"; }

check() { # check "<what it proves>" <condition-as-string>
  if eval "$2"; then pass "$1"; else fail "$1 — [$2]"; fi
}

psql_() { "${COMPOSE[@]}" exec -T postgres psql -qtAX -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"; }

cleanup() {
  if [[ "${SMOKE_KEEP:-}" == "1" ]]; then
    printf '\nSMOKE_KEEP=1, leaving %s up\n' "$PROJECT"
    return
  fi
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

printf 'smoke: %s (project %s)\n' "$IMAGE" "$PROJECT"
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d postgres greenmail wiremock >/dev/null

# ---------------------------------------------------------------------------------------
section "boots, and is not a fallback image"
# The clock starts before the container, not after: what matters is the window a Recreate
# rollout is down for, and container start is inside it.
t0=$(now_ms)
"${COMPOSE[@]}" up -d smoke-api >/dev/null
deadline=$((SECONDS + 120))
until curl -sf "$API/actuator/health" >/dev/null 2>&1; do
  if ((SECONDS > deadline)); then
    fail "healthy within 120 s"
    "${COMPOSE[@]}" logs --no-color smoke-api 2>&1 | tail -60
    exit 1
  fi
  sleep 0.1
done
healthy_ms=$(( $(now_ms) - t0 ))
logs=$("${COMPOSE[@]}" logs --no-color smoke-api 2>&1)

check "health reports UP" '[[ $(curl -sf "$API/actuator/health" | tr -d " ") == *\"status\":\"UP\"* ]]'
check "time-to-healthy ${healthy_ms} ms is within ${BOOT_BUDGET_MS} ms" '((healthy_ms <= BOOT_BUDGET_MS))'
# A fallback image is a JVM with a native launcher stapled to it. It boots, it passes every
# assertion below, and it is 200 MB — so it has to be refused by name rather than by symptom.
check "not a GraalVM fallback image" '! grep -qi "fallback image" <<<"$logs"'
# An AOT cache the JVM rejected is a warning and is otherwise indistinguishable from one it
# used. Only asserted when the image was built to have one.
if grep -q "AOTCache" <<<"$(docker image inspect --format '{{json .Config.Entrypoint}}' "$IMAGE")"; then
  check "the AOT cache was opened, not rejected" '! grep -qiE "aot .*(disabled|error|cannot|failed)" <<<"$logs"'
fi

# ---------------------------------------------------------------------------------------
section "Flyway ran, and pgvector is there"
# The classic native failure is not an exception: the classpath is scanned, no migrations
# are found, the schema is empty, every query returns zero rows and the service is healthy.
# So this counts them.
expected=$(ls backend/src/main/resources/db/migration/V*.sql | wc -l | tr -d ' ')
applied=$(psql_ -c "SELECT count(*) FROM flyway_schema_history WHERE success")
check "all $expected migrations applied (found $applied)" '[[ "$applied" == "$expected" ]]'
check "the vector extension exists" '[[ $(psql_ -c "SELECT count(*) FROM pg_extension WHERE extname = '"'"'vector'"'"'") == 1 ]]'

# ---------------------------------------------------------------------------------------
section "all four configuration files bound, from both layers"
# One file on disk and three off the classpath, so one run proves the lookup in both
# directions. The classpath direction is the one a native image breaks.
sources=$(curl -sf "$V1/sources" || true)
check "sources.yaml came off the disk layer" '[[ "$sources" == *smoke-corpus* && "$sources" == *smoke-imap* ]]'
# Reading one back exercises SnakeYAML's compose() and the masking that cuts the block out
# of the file's own bytes — a second, independent parser on the same file.
check "a single source block renders and is masked" '[[ -n $(curl -sf "$V1/sources/smoke-imap" | tr -d "[:space:]") ]]'
check "matching-rules.yaml bound (classpath layer)" '[[ $(curl -sf "$V1/rules" | wc -c) -gt 50 ]]'
check "pipeline.yaml bound (classpath layer)" '[[ $(curl -sf "$V1/prompts" | wc -c) -gt 50 ]]'
check "the scoring model allowlist bound" '[[ $(curl -sf "$V1/scoring-models") == *smoke-model* ]]'
# ConfigurationBanner reads the same files a second time through a different call. Cheapest
# possible proof that that path resolves too.
check "the banner resolved files from the classpath" 'grep -qi "classpath" <<<"$logs"'

# ---------------------------------------------------------------------------------------
section "ingest reads a mailbox and a directory"
# GreenMail is the only thing here that touches jakarta.mail: provider discovery through
# META-INF/javamail.*, the ImapMailReceiver built by hand at runtime and configured through
# BeanFactory callbacks, Spring Integration's SpEL evaluation context, and the
# jakarta.mail.search.* term classes. All four fail silently as "zero documents".
curl -sf -X POST "$GREENMAIL/api/mail" -H 'Content-Type: application/json' \
  -d "$(python3 - <<'PY'
import json, pathlib
body = pathlib.Path("demo/corpus").glob("*.eml")
raw = sorted(body)[0].read_text(encoding="utf-8", errors="replace")
print(json.dumps({"to": "smoke@example.invalid", "from": "newsletter@example.invalid",
                  "subject": "smoke", "body": raw}))
PY
)" >/dev/null 2>&1 || true

check "the run endpoint answered" 'curl -sf -X POST "$V1/ingest" >/dev/null'
last=$(curl -sf "$V1/ingest/last" || true)
check "the file source read the corpus" 'python3 -c "
import json,sys
r=json.loads(sys.argv[1])
t=json.dumps(r)
sys.exit(0 if \"smoke-corpus\" in t else 1)" "$last"'
check "the IMAP source connected without a provider error" '! grep -qiE "NoSuchProviderException|no provider for imap" <<<"$("${COMPOSE[@]}" logs --no-color smoke-api 2>&1)"'
check "the corpus produced offers" '[[ $(psql_ -c "SELECT count(*) FROM offer") -gt 0 ]]'

# ---------------------------------------------------------------------------------------
section "a package renders through Freemarker"
# One assertion covering: new Configuration(VERSION_2_3_34) reading freemarker/version.properties,
# BeansWrapper's static init reading unsafeMethods.properties, the Introspector over
# SkillProfile and ProjectView, the {lang} name computed at runtime, ConfigSource.resolve
# for a .ftl off the classpath, and the @Async @TransactionalEventListener in PackageWorker.
"${COMPOSE[@]}" exec -T postgres psql -qtAX -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  < backend/smoke/seed.sql >/dev/null
app_id=$(psql_ -c "SELECT a.id FROM application a JOIN offer o ON o.id = a.offer_id WHERE o.external_id = 'smoke-1'")
offer_id=$(psql_ -c "SELECT id FROM offer WHERE external_id = 'smoke-1'")
code=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "$V1/applications/$app_id" \
        -H 'Content-Type: application/json' -d '{"status":"PACKAGED"}')
check "the application moved to PACKAGED (got $code)" '[[ "$code" == 200 ]]'

# The folder is built after the status write commits, in the background, so this polls.
zip=$(mktemp); deadline=$((SECONDS + 60))
until curl -sf -o "$zip" "$V1/offers/$offer_id/package" 2>/dev/null; do
  if ((SECONDS > deadline)); then break; fi
  sleep 0.5
done
check "the package is a readable zip" 'unzip -l "$zip" >/dev/null 2>&1'
letter=$(unzip -p "$zip" cover_letter.txt 2>/dev/null || true)
check "the cover letter rendered" '[[ -n "$letter" ]]'
check "the letter carries the offer title" '[[ "$letter" == *"Senior Backend"* || "$letter" == *"Senior Java Entwickler"* ]]'
check "the letter names the portal and not the agency" '[[ "$letter" == *smoke-portal* && "$letter" != *"Smoke Consulting"* ]]'
check "no line of the letter is indented" '! grep -qE "^[[:space:]]+[^[:space:]]" <<<"$letter"'
rm -f "$zip"

# ---------------------------------------------------------------------------------------
section "an SDK response deserializes"
# spring-ai -> the vendor SDK -> its HTTP client -> Jackson. The Anthropic SDK ships no
# reachability metadata at all and the OpenAI one ships its own, so which of the two this
# ran against is the single most important line of the whole suite.
curl -sf -X DELETE "$WIREMOCK/__admin/requests" >/dev/null || true
scored=$(curl -sf -X POST "$V1/offers/$offer_id/score" || true)
provider="${SMOKE_LLM_PROVIDER:-openai-compatible}"
# A pattern, for the same reason the stub uses one: the /v1 prefix is the client's to
# add or not, and asserting on the literal path would make a routing change look like a
# deserialization failure.
path=$([[ "$provider" == anthropic ]] && echo '(/v1)?/messages' || echo '(/v1)?/chat/completions')
# `|| echo 0` and not a bare pipeline: `pipefail` is on, so an unreachable WireMock would
# end the run here instead of reporting the count check as the failure it is.
served=$(curl -sf -X POST "$WIREMOCK/__admin/requests/count" \
          -H 'Content-Type: application/json' -d "{\"method\":\"POST\",\"urlPathPattern\":\"$path\"}" \
          | python3 -c 'import json,sys; print(json.load(sys.stdin)["count"])' || echo 0)
# A 200 produced by the judge giving up looks exactly like a 200 produced by the judge
# working, so the stub having been called is asserted separately from the result.
check "the $provider endpoint was actually called (count $served)" '[[ "$served" -ge 1 ]]'
check "the stubbed verdict came back through the SDK" '[[ "$scored" == *role_fit* || $(psql_ -c "SELECT count(*) FROM offer_score_reason r JOIN offer o ON o.id = r.offer_id WHERE o.external_id = '"'"'smoke-1'"'"' AND r.factor = '"'"'role_fit'"'"'") -ge 1 ]]'

# ---------------------------------------------------------------------------------------
section "the numbers"
mem=$("${COMPOSE[@]}" exec -T smoke-api sh -c 'cat /sys/fs/cgroup/memory.current' 2>/dev/null \
      || docker stats --no-stream --format '{{.MemUsage}}' "$("${COMPOSE[@]}" ps -q smoke-api)")
size=$(docker image inspect --format '{{.Size}}' "$IMAGE")
row=$(printf '| %s | %s ms | %s | %s |' "$IMAGE" "$healthy_ms" \
        "$(python3 -c "print(f'{int('${mem:-0}')/1048576:.0f} MiB')" 2>/dev/null || echo "$mem")" \
        "$(python3 -c "print(f'{$size/1000000:.0f} MB')")")
printf '\n| image | time-to-healthy | memory after the run | image size |\n|---|---|---|---|\n%s\n' "$row"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  { printf '| image | time-to-healthy | memory after the run | image size |\n|---|---|---|---|\n'; printf '%s\n' "$row"; } \
    >> "$GITHUB_STEP_SUMMARY"
fi

printf '\n'
if ((failures > 0)); then
  printf '\033[31m%d check(s) failed\033[0m\n' "$failures"
  exit 1
fi
printf '\033[32mall checks passed\033[0m\n'
