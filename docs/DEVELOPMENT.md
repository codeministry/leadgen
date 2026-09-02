# Development

## Prerequisites

| Thing | Version | Note |
|---|---|---|
| JDK | **21** | Pinned through the Gradle toolchain, not taken from the ambient JDK — that is what makes the build produce the same bytecode here and in CI. |
| Gradle | — | Use the wrapper (`./gradlew`). |
| bun | **1.3+** | The package manager for the frontend. Never npm or npx. |
| Docker | any recent | Required for `docker compose`, **and for `./gradlew :backend:test`** — the backend tests use Testcontainers. |
| Postgres | 17 | Supplied by Compose. Published on host port **55432**, not 5432. |

The host port is 55432 on purpose: a developer machine usually already has a Postgres on
5432, and connecting to the wrong one fails as `password authentication failed for user
"leadgen"` — a message naming the user and neither the host nor the database it actually
reached. `DatasourceBanner` prints the effective JDBC URL at startup for the same reason.

## Commands

```bash
./gradlew check                # both modules — the gate
./gradlew :backend:test        # Spring tests
./gradlew :backend:bootRun     # API on :8080, reads the untracked .env from the repo root
./gradlew spotlessApply        # formatting and the SPDX header, applied

docker compose up postgres     # just the database a local run expects
docker compose up --build      # the whole stack
docker compose -f docker-compose.yml -f docker-compose.demo.yml up --build   # …with the demo data

cd frontend
bun run start                  # dev server :4200, proxies /api to API_PROXY_TARGET
bun run check:static           # ESLint (--max-warnings 0), Stylelint, tsc
bun run test                   # Vitest
bun run test:coverage          # …with a v8 coverage report
```

`./gradlew check` is the whole gate: Spotless, JaCoCo and the Spring tests for the backend,
lint and Vitest for the frontend. The frontend is bracketed with plain `Exec` tasks calling
`bun` rather than with the Node Gradle plugin — the plugin does not speak bun, and this way
`package.json` stays the single list of frontend commands and `bun run <script>` behaves
identically inside and outside Gradle.

## Where the settings come from

Everything individual lives in two gitignored places: `.env` at the repository root, and
`config/` beside it. Neither is committed, and the tool runs without either — the defaults
ship on the classpath. Start from [`.env.example`](../.env.example); the full list of keys
is in [CONFIGURATION.md](CONFIGURATION.md).

`.env` is read by the application itself, not by the build, and it is searched upwards from
the working directory with real environment variables winning. That matters because the
working directory is not one thing: `bootRun` runs in `backend/`, an IDE run configuration
in the repository root, a jar wherever it sits. It used to be a `bootRun` hook, which meant
launching the very same configuration from an IDE silently saw none of it.

Compose reads the same file, and it has to be called `.env`: Compose substitutes the
`${...}` in `docker-compose.yml` from `.env` and from nothing else — not from `env_file:`,
which only injects into a container.

**Reading a run:** the backend prints a one-box configuration banner on startup naming, per
setting, the effective value and which layer decided it, with credentials masked. The dev
server prints its effective proxy target (`[proxy] /api → …`). Those two lines are the first
place to look for an unexplained empty list.

## The demo dataset

`demo/` holds an invented corpus, profile and rule set so a fresh clone opens on a populated
application. It is also the fastest way to exercise a change end to end without a mailbox.
See [`demo/README.md`](../demo/README.md).

## The reference implementation

`docs/samples/analyze_samples.py` and `docs/samples/simulate_filter.py` are the reference:
whatever they do, the Java has to reproduce, and the numbers in
[SAMPLE-ANALYSIS.md](SAMPLE-ANALYSIS.md) are the target values. They are the only Python in
the repository and they stay.

```bash
python3 docs/samples/analyze_samples.py    # extraction, field coverage, duplicates
python3 docs/samples/simulate_filter.py    # the hard filters, writing filter-baseline.json
```

Both need `docs/samples/emails/`, which is **gitignored**: the corpus is 14 real newsletter
mails carrying a subscriber's address in every header and unsubscribe link. So on a fresh
clone and in CI, `HardFilterCorpusTest` and `SampleCorpusAcceptanceTest` skip. `ExtractionTest`
covers the same mechanics against a fixture that ships, and the two must stay in step.

## Traps that have already cost time

The full list lives in [`CLAUDE.md`](../CLAUDE.md); these are the ones a newcomer hits first.

- **`bun run check:static` says nothing about the templates.** `tsc -p tsconfig.app.json`
  does not run the Angular template compiler, so a template type error only surfaces in
  `bun run test` or `bun run build`.
- **Tailwind 4 scans source *text* for class names.** A class assembled at runtime
  (`'badge-' + tone()`) is never emitted into the stylesheet — it exists in the DOM and
  nowhere else. Spell every variant out in a literal lookup map.
- **A component class name must not collide with a DaisyUI component class.** DaisyUI ships
  `status`, `label` and others; a header span classed `.status` was silently laid out as an
  8 px dot.
- **Router input binding writes `undefined` for an absent query parameter**, overriding the
  input's declared default. Every routed input needs `transform: (value) => value ?? …`.
- **Verify UI changes in a real browser, not only in the suite.** Renaming the root
  component's selector without editing `src/index.html` leaves every unit test passing and
  the page blank, with nothing in the console.
- **A backgrounded browser tab suspends `requestAnimationFrame`, `ResizeObserver` and CSS
  transitions** without erroring, so anything animated or viewport-dependent measures as
  "nothing happened". Measure those in a foreground window.
