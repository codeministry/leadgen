<img src="../brand/leadgen.png" alt="LEADgen / AI" height="28">

# The AOT cache, the native image, and the hints written by hand

Why leadgen is compiled twice, what each artifact is worth, and which reachability metadata
this repository has to write because nobody upstream does.

## What this is for, in order

leadgen is a long-running server with a scheduler, not a function that starts per request,
so startup time is the *least* of the three reasons this exists. In order:

1. **Memory on a small ARM instance.** The deploy target is one; the JVM image's resident
   set is the number that sizes it.
2. **The rollout window.** The api deployment is `Recreate`, so every deploy is down for as
   long as the process takes to start.
3. **It is a public repository.** Spring Boot 4.1 with Spring AI 2.0 compiled natively is
   barely documented anywhere, and the parts of it that were work are worth writing down
   whether or not the image is ever adopted.

Read that order twice before optimising anything here: it is why the runtime base is glibc
and not a 15 MB static musl binary, and why `--gc=serial` and a long-running heap beat image
size every time they disagree.

## The two artifacts, and which one is the default

`leadgen-api` is the JVM image and stays the default. `leadgen-api-native` is a second,
opt-in image that is built, smoke-tested and published beside it. Switching the default is a
separate decision and is not taken here.

The reason is the failure mode rather than the risk. A missing reflection hint is not a build
error and usually not a startup error either — it is an empty result in one stage, which in
this pipeline means "every offer silently unscored" on a service that reports healthy. That
is a defect an opt-in image surfaces in a smoke run and a default image surfaces in the
morning.

## Baseline

Measured with `backend/smoke/measure.sh`, which is a file and not a runbook paragraph
because these numbers are taken at least three times and a comparison whose two halves were
measured by hand is not a comparison.

Two startup numbers, because they differ here by a lot and only one of them is a rollout
window:

- **time-to-healthy** — wall clock from `docker run` to the first 200 on `/actuator/health`.
- **Started in** — Spring's own line, which excludes container and JVM start. The gap
  between the two is the part no AOT work touches.

Two rounds, because the first start migrates 27 Flyway files into an empty database (what a
new deployment sees) and the second does not (what every restart after that sees).

Resident memory is read three times — right after healthy, after 60 s idle, and after an
ingest — because only the third number sizes an instance. On Linux that is `VmRSS` of PID 1;
under Docker Desktop the host cannot read the container's `/proc`, so the script falls back
to cgroup `memory.current`, which includes page cache and is therefore comparable to itself
and not to a `VmRSS` taken elsewhere. The script prints which one it used.

### JVM, 2026-09-20

`leadgen-api:baseline`, built from `backend/Dockerfile` at 0.4.0 + the working tree.
Measured on an Apple-silicon Mac under Docker Desktop, linux/arm64, against an empty
Postgres in its own compose project. **Five rounds**, because two were not enough — see
below.

| | median | range over 5 rounds |
|---|---|---|
| image, on disk | 349 MB | — |
| time-to-healthy | **6.43 s** | 4.92 – 7.98 s |
| `Started … in` | **5.39 s** | 3.89 – 7.06 s |
| memory, 60 s idle | **271 MiB** | 265 – 283 MiB |

Memory is cgroup `memory.current` and therefore includes page cache; it is comparable to
the rows below it and not to a `VmRSS` taken on Linux.

**The startup numbers on this machine are too noisy to judge an AOT cache by, and that is
the first result.** The spread is ±24 % around the median — wider than the 15–25 % the
Spring AOT step is expected to buy, so a single before-and-after pair on a laptop could
show an improvement, no change, or a regression, whichever two runs were picked. Two
consequences, both of which the plan already wanted for other reasons: the authoritative
startup measurement is the one a Linux CI runner takes, which is why `smoke.sh` runs in the
`images` job of `ci.yml` against the image that job just built and prints its row into the
job summary; and a local run is good for catching a *large* change and for nothing finer.
`measure.sh` is *not* wired into that. It sleeps 60 s per round and answers a different
question — is image A better than image B — which is a thing somebody asks deliberately and
not a thing a gate should spend six minutes on every push.

Memory is the opposite: ±3 % across the same five rounds, stable enough to compare
directly. Which is convenient, because it is also the first of the three goals.

A second thing the five rounds settled: **the 27 Flyway migrations are not the dominant
startup cost.** Round 1 migrates an empty database and rounds 2 and 3 do not, and round 1
was the *fastest* of the five. The cold-versus-warm distinction the script still measures is
worth keeping, but on this evidence it is below the noise.

## Stage 1: Spring AOT and the extracted layout — 2026-09-20

Same machine, same Postgres, five rounds each, medians. The third column is the image
`backend/Dockerfile` now builds.

| | fat jar, no AOT | extracted, no AOT | **extracted + Spring AOT** |
|---|---|---|---|
| time-to-healthy | 6.43 s | 3.67 s | **3.06 s** |
| `Started … in` | 5.39 s | 3.17 s | **2.55 s** |
| memory, idle | 271 MiB | 248 MiB | **237 MiB** |
| image, on disk | 349 MB | 350 MB | **350 MB** |

**Most of that is not Spring AOT.** Unpacking the jar accounts for 43 % of the startup
improvement on its own and Spring AOT adds a further 17 % on top of it — the middle column
is the *same image* as the third, started without `-Dspring.aot.enabled=true`, so the split
is measured and not apportioned. What the extracted layout removes is Boot's nested-jar
class loader, which reads every dependency through a jar inside a jar. That is worth
knowing because the two halves carry different risk: unpacking changes nothing about what
the application decides, while `processAot` freezes condition outcomes into the artifact.
If the AOT half ever has to be switched off, two thirds of this is still there.

**Against the noise floor.** The five baseline rounds spanned 4.92–7.98 s and the five AOT
rounds 2.78–3.71 s. The ranges do not overlap, so this is a real difference even on a
measurement this noisy — which is the only reason it is quoted from a laptop at all. The
AOT image is also steadier (±15 % against ±24 %), which is what removing work from the
startup path does.

**It beat the estimate.** The plan expected 15–25 % from Spring AOT and budgeted a second
stage — a recorded JDK AOT cache — to get further. Halving the startup instead raises the
bar for that stage considerably: it now has to be worth its own Dockerfile, its own CI job
and a divergence between what `docker compose up` builds and what the registry carries,
against a rollout window that is already three seconds.

**Memory moved 13 %**, which is the goal that actually matters here and the one least served
so far. A native image is the stage that addresses it.

## The smoke suite, and why there is no `nativeTest`
### Applying the plugin is not free, and the bill arrives in `check`

The plan said `nativeCompile` would not be wired into `check` and that `./gradlew check`
would stay what it was. The first half is true and the second was wrong. Applying
`org.graalvm.buildtools.native` makes Boot register AOT processing for the **test** source
set as well — `processTestAot`, `compileAotTestJava`, `processAotTestResources` — and that
chain lands in the `check` graph.

`processTestAot` starts every `@SpringBootTest` context at build time. In this repository
that is 30 test classes, each with a Testcontainers Postgres. Measured: `check` went from
2 m 25 s to still running after fifty minutes, with nothing in the output naming the task
as the reason — the run simply stops producing lines.

All three tasks are disabled, not just the first: disabling `processTestAot` alone leaves
`compileAotTestJava` running against output that was never produced, and the build then
fails for a second reason that looks unrelated to the first. With them off, `check` is
2 m 51 s and 615 tests, which is where it was.

Re-enable the chain only together with the decision it belongs to — that a compiled image
is checked by `nativeTest` rather than by `backend/smoke/smoke.sh`.


`nativeTest` compiles the test suite into a native binary and runs it. Here that is not
practical and, more importantly, not the thing worth testing: 30 of 77 test classes start a
Testcontainers Postgres and 31 use `@DynamicPropertySource`, whose supplier is called
whenever the property is resolved and which AOT cannot fold. Tagging a subset that survives
would leave exactly the paths that matter — IMAP, packaging, the model call — outside it,
because all three need a database.

So the JVM suite stays the authority on behaviour, and `backend/smoke/smoke.sh` checks the
things that are properties of the artifact rather than of the code: seven groups,
twenty-four checks, run against a finished image. Each one asserts on a *result*, never on
the absence of a stack trace, because the native failure mode is an empty result from a
healthy service.

It is a bash script and a compose **overlay**, not a Gradle task and not a compose file of
its own. Not a Gradle task because it runs a container on a runner and dragging the
configuration cache and the toolchain into that buys nothing. An overlay because
`pgvector/pgvector:0.8.6-pg18` is named in exactly two places in this repository and a
third copy is a third thing to forget on the next major — `postgres` is inherited verbatim
and only GreenMail, WireMock and the service under test are added. That service is called
`smoke-api` and not `api`, because `api` carries a `build:` block an overlay cannot remove
and overriding its image would still build from source.

The configuration directory it mounts holds **one** file, `sources.yaml`. The other three
come off the classpath, so a single run proves the two-layer lookup in both directions —
and the classpath direction is the one that breaks, because a resource reached by a name
computed at runtime is not there at all.

Two things it found on the first run, neither of which a unit test would have:

- **Spring AI's OpenAI client posts to `{base_url}/chat/completions`, not
  `/v1/chat/completions`.** A stub on the literal path is served a 404, and the judge then
  reports *"smoke-model did not answer with a usable judgement"* — a message about the
  model, for a routing mistake. Both stubs match by pattern now.
- **A container started by `docker compose up` is ~7 s slower to healthy than the same
  image started by `docker run`** (9.8 s against 3.1 s). The suite's budget covers it, but
  the number in its table is not the number in the tables above and the two must not be
  compared. `measure.sh` is the instrument for comparing images; `smoke.sh`'s number is a
  regression guard.

What it does **not** prove, stated so that nobody reads a green run as more than it is:

- **The two model stubs are written from the published response shape, not recorded from a
  live call.** They prove the SDK's deserializer can be reached and can build a response
  object in the runtime it is running in, which is exactly what a native image breaks. They
  do not prove the shape is current. Replacing them with scrubbed recordings is the one
  step in this suite that needs an API key.
- **TLS.** The suite talks plain HTTP to WireMock and plain IMAP to GreenMail. A native
  image bakes the build JDK's `cacerts` into the binary, and nothing offline can show that
  the baked list is still current — that is a rebuild concern, and it is listed as one.

<!-- STAGE 2 TABLE -->

## What runs at startup

Worth knowing before attributing any improvement to AOT, because most of this is I/O and
none of it gets faster:

1. `DotEnvEnvironmentPostProcessor`, via `META-INF/spring.factories`, searching upwards for
   `.env` before the environment is bound.
2. Flyway, 27 migrations; `V22` runs `CREATE EXTENSION IF NOT EXISTS vector`.
3. `ConfigRegistry`'s **constructor**: four YAML files resolved across two layers, placeholder
   expansion over each file's text, Jackson binding to four record trees with
   `FAIL_ON_UNKNOWN_PROPERTIES`, Jakarta validation, then the cross-file consistency check.
   An invalid configuration is fatal here by design.
4. Three `ApplicationReadyEvent` listeners, of which `ConfigurationBanner` re-reads all four
   YAML files from **both** layers plus `.env`, and `DatasourceBanner` opens a second JDBC
   connection.

Steps 1, 2 and 4 are disk and network. What an AOT cache and a native image remove is JVM
bootstrap, class loading, and the reflective half of step 3.

## Moved from the root CLAUDE.md

- **Pre-1.0 a breaking change is a PATCH; only the runtime moves the MINOR.** SemVer would ask
  for a minor bump on a broken API or schema; this repository does not, and will not until 1.0.
  A breaking `/api/sources` response shape shipped as `v0.3.1`, and the dropped `ingest_cursor`
  table plus changed `remote.accept_unknown` semantics as `v0.3.2`. The minor is kept for a
  change in how the artifact is built or run: `v0.4.0` ships the jar unpacked with Spring AOT
  switched on, and carries three breaking changes along with it. The breaking part belongs in
  the release notes, not in the number.
