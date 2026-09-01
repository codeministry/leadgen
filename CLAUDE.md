# CLAUDE.md

Acquisition tool for freelancers. Collects project offers from configured sources,
filters them against a profile, enriches the survivors, scores them and assembles a
ready-to-send application package. **No automatic sending.**

Concept: `docs/CONCEPT.md`. Measured baseline: `docs/SAMPLE-ANALYSIS.md`.
Why the pipeline grew an enrichment stage: `docs/CONCEPT-addendum-enrichment.md`.

## Language

**Everything in this repository is English.** Code, identifiers, comments, config
comments, documentation, commit messages, issues, UI strings, log output, test names.
No exceptions, and no German creeping back in over time.

The one thing that is *not* repo language but data: the offers this tool reads are
German, the cover letters it writes are German, and the reference-project pitches in
the profile are German. That is **content**, it lives in `config/local/` and in i18n
catalogs, and it is selected by the language of the job ad — never hardcoded.

If you find German anywhere else, translate it in the same change. Do not add a
German comment "just this once".

## Repo-wide invariants

Violating one of these is expensive, and most of them fail silently.

- **Nothing is wired in.** This repo is going public. No newsletter name, no portal,
  no mail provider, no model name and no personal datum belongs in the code. Everything
  individual lives in `config/local/` (gitignored) and in `.env`. A new source is a YAML
  block, not a deploy.
- **Rules before model.** The hard filter runs deterministically and for free before any
  LLM call. Without a language model the tool must still run, only weaker.
- **No CV tailoring.** Fixed PDFs in `config/local/documents/`, selected by the language
  of the ad and nothing else.
- **The mail address never leaves the machine.** Newsletter links are proxied as
  `…/proxy?target=…&email=…`. Unwrap `target`, discard `email`. Raw `.eml` files and
  anything derived from them are gitignored — they carry the address in headers and
  unsubscribe links.
- **`min_hourly_eur` must not apply before the enrichment stage.** The newsletter carries
  a rate in 0.0 % of offers. Applied earlier, the rule filters either everything or nothing.
- **Never commit.** Do the work, leave it uncommitted, offer the commit — Marcello decides.

## Monorepo

`backend/` (Spring Boot 4.1, Java 21, Gradle) · `frontend/` (Angular 22 zoneless +
`@ngrx/signals` + Tailwind 4/DaisyUI) · `charts/` (Helm) · `config/` · `docs/`.
The root Gradle build brackets both: `./gradlew check` runs the Spring tests and the
frontend's lint + tests in one call.

**The frontend is bracketed with plain `Exec` tasks calling `bun`, not with the Node
Gradle plugin** — the plugin does not speak bun, and bun is the package manager
everywhere in this house. The consequence is that `package.json` stays the single list
of frontend commands and `bun run <script>` behaves identically inside and outside
Gradle.

## Commands

```bash
./gradlew check                # both modules
./gradlew :backend:test        # Spring tests — needs a running Docker for Testcontainers
./gradlew :backend:bootRun     # API on :8080, reads the untracked .env from the repo root
docker compose up --build      # postgres + api + web

cd frontend
bun run start                  # dev server :4200, proxies /api to API_PROXY_TARGET
bun run check:static           # ESLint (--max-warnings 0), Stylelint, tsc — after every change
bun run test                   # Vitest
```

**Never npm or npx.** bun installs, runs and locks the frontend (`bun.lock`).

## Frontend conventions

Carried over from `codeministry/customer/ship360`, which is the house style:

- **Layering is strict**: `shared` → `core` → `layout` → `features`. Cross-layer imports
  go through the tsconfig aliases (`@core/*`, `@shared/*`, `@features/*`, `@layout/*`),
  because that is what the `no-restricted-imports` patterns in `eslint.config.mjs` match
  on — a relative `../../core/...` slips past the rule. Relative imports only between
  siblings. **No barrels** (`index.ts`).
- **`shared/` imports nothing from the layers above it**, not even types.
- **Standalone components, signals, `OnPush`, zoneless.** `input()`/`output()`/`model()`,
  `signal`/`computed`, `inject()`, `@if`/`@for`. No `@Input/@Output`, no `*ngIf`, no
  `| async`. RxJS only at the I/O boundary, bridged in with `toSignal`.
- **`.css`, never `.scss`** — Tailwind 4 is CSS-first. No raw hex under `src/app`
  (`color-no-hex`); literals live in `src/styles/tokens.css`.
- **NgRx**: `@ngrx/signals` events dialect, stores as a `*.store.ts` + `*.events.ts`
  pair with `withReducer` + `withEventHandlers`. Model: `core/store/status.store.ts`.
- **Specs live beside their file.**
- **Strict TypeScript** plus `strictTemplates`, `noPropertyAccessFromIndexSignature`,
  `noImplicitReturns`, `noImplicitOverride`, `noUnusedLocals`. No `baseUrl` — TypeScript
  6 deprecates it and the path mappings resolve relative to `tsconfig.json` anyway.

## The configuration layer

`backend/…/config/`. Everything else reads `ConfigRegistry.snapshot()` and nothing
reads a YAML file itself.

- **The three files are one snapshot**, read together and swapped atomically. Reloading
  one without the others would hand the pipeline a picture that never existed on disk,
  which is why `rules.hot_reload` is one switch for all three.
- **Binding is strict** (`FAIL_ON_UNKNOWN_PROPERTIES`). A misspelled `min_remote_percent`
  would otherwise disable a hard filter in silence, and the only visible effect is a
  longer shortlist — which looks exactly like a good day on the market.
- **The failure policies differ by design.** Invalid at startup is fatal: running with a
  filter nobody wrote is worse than not running. Invalid at reload is not: the last good
  snapshot stays and the problem is logged, because a half-saved file must not take the
  running tool down.
- **`min_hourly_eur` before enrichment is rejected at load time** — the invariant is
  enforced, not just written down.
- **Placeholder resolution is deliberately dumb.** `${VAR}` without a value becomes the
  empty string, and an empty YAML scalar is **null**, not `""` — every consumer treats
  both alike. Whether empty is acceptable is a question about the field, so validation
  answers it: an unset LLM key is fine, an unset IMAP host on an *enabled* source is not.
- **Paths in `application.yaml` are file names**, resolved against the config directory.
  A path with the directory baked in breaks the moment it moves — in the container it is
  `/config`, not `./config/local`.
- **Change detection polls timestamps, it does not use `WatchService`.** For three files
  the efficiency argument is worth nothing, and the watch service is native only on
  Linux; on macOS the JDK falls back to polling with a ten-second default latency. A
  change is applied one cycle after it is first seen, so a save in progress finishes
  first.
- **The name `application.yaml` means two different files.** The one on the classpath
  wires the process; the one in the config directory is the tool's own configuration. A
  stack trace naming it can mean either.

## Ingest and extraction

`backend/…/ingest/`. A connector fetches documents, `HtmlBlockExtractor` splits them into
blocks and reads fields, `OfferMapper` turns a block into an `ExtractedOffer`, `OfferStore`
upserts it. `POST /api/ingest` runs one pass.

- **No selector is written in Java.** Block selector, every field, the date format and the
  proxy parameter all come from the source's `extraction` section. That is what makes a
  new source a YAML block. The worked example is `local-eml` in
  `config/examples/sources.example.yaml`.
- **The eight field names are the contract** between `sources.yaml` and `OfferMapper`:
  `title`, `url`, `description`, `location`, `portal`, `agency`, `published`, `tags`. A
  field spelled differently is extracted and then ignored, in silence.
- **Meta fields are addressed by their emoji prefix, never by position.** Four spans sit in
  one row and 9.2 % of offers state no company — read by position, every following field of
  those offers is shifted by one.
- **`ProxyLink.unwrap` is a privacy boundary, not a convenience.** Every link in the corpus
  carries the subscriber's address as a query parameter. An unrecognised wrapper therefore
  loses its whole query rather than keeping it. `SampleCorpusAcceptanceTest` fails on an
  `@`, an `email=` or a `%40` in any of the 1289 URLs.
- **Extraction needs no language model** for this source: `fallback: none`, and the count
  the subject announces matches in all 14 mails.
- **`published_on` keeps the date and drops the time.** The source states a time without a
  zone, which cannot become an instant without guessing; the freshness rule counts days.
- **The upsert on `(source_id, external_id)` is what makes re-reading free.** A newsletter
  repeats what is still open, so re-reading is the normal case. `written` in the report
  counts rows touched, insert or update alike — 1289 offers extracted become 1280 rows,
  because nine listings appear in two mails. **That difference is not deduplication**:
  dedupe collapses one *project* advertised by several portals, this collapses one
  *listing* seen twice.
- **The acceptance test is skipped without the corpus.** `docs/samples/emails/` is
  gitignored, so it is absent on a fresh clone and in CI. `ExtractionTest` covers the same
  mechanics against a fixture that ships, and that one must stay in step.
- **The `<mark>` trap is not reproducible in the current corpus** — zero occurrences in all
  14 mails. jsoup's `text()` strips it regardless, and `ExtractionTest` guards it, but treat
  it as an expectation rather than a measurement.

## Backend conventions

- **JDBC, not JPA.** The pipeline writes offers in batches and upserts them with
  `ON CONFLICT`, which is one statement of plain SQL against a schema Flyway owns. An ORM
  would add a mapping layer over Postgres arrays for no gain. Flyway is therefore the only
  thing that touches the schema at all.
- **Boot 4 split the integrations into their own modules.** Without
  `spring-boot-flyway` the migrations sit on the classpath and never run, and the only
  symptom is Hibernate complaining about missing tables. `@WebMvcTest` likewise moved
  from `…test.autoconfigure.web.servlet` into `spring-boot-webmvc-test`.
- **The `.env` is read by `bootRun`, never through `spring.config.import`** in
  `application.yaml` — an import there would apply to every test context too, and a
  green test run would then depend on a file nobody sees in the repo.

## What already exists

```
config/local/sources.yaml         IMAP mailbox, verified aggregator selectors, 6 sources
config/local/matching-rules.yaml  hard filters + scoring weights
config/local/application.yaml     LLM, enrichment, packaging, digest
config/local/skill-profile.yaml   skills with weights and aliases, reference projects
config/examples/*.example.yaml    neutral versions for the public repo
.env.example
docs/samples/emails/*.eml         14 real newsletter mails (gitignored)
docs/samples/analyze_samples.py   extraction, field coverage, duplicates
docs/samples/simulate_filter.py   simulation of the hard filters
```

The two Python scripts are the **reference implementation**. Whatever they do, the Java
code has to reproduce — the numbers in `docs/SAMPLE-ANALYSIS.md` are the target values.

## Measured baseline

- 14 mails, **1289 offers**, all extracted deterministically via CSS. The count announced
  in the subject matches exactly in all 14. `fallback: none` for this source.
- **0.0 % contain an hourly rate.** Rate, duration, workload and start date only arrive
  from the enrichment stage (fetching the original ad from the portal).
- The hard filter lets **16.5 %** through, **~15 per mail** after deduplication. That is
  the daily LLM budget.
- **12.3 % duplicates** by exact title alone, within a single mail.

## Order of work

1. ✅ **Monorepo skeleton** — root build, `backend/` skeleton, `frontend/` skeleton,
   `docker-compose.yml` (postgres, api, web), `.env` loading, Flyway. `GET /api/status`
   plus the `StatusStore` exist only to prove the full path (component → proxy → Spring
   → Postgres) end to end; they are not a feature.
2. ✅ **Configuration layer** — load, validate and hot-reload `sources.yaml`,
   `matching-rules.yaml`, `application.yaml`. First, because everything else stands on it.
   `ConfigRegistry.snapshot()` is how the rest of the code reads configuration.
3. ✅ **Ingest + extract** against the `local-eml` source (files, no mailbox needed).
   Acceptance test: 1289 offers from `docs/samples/emails/`, field coverage as in the analysis.
4. **IMAP connector** — same extraction, different source. Progress tracked via
   `UIDVALIDITY`/`UID`, **never** via seen/unseen: the user reads the same mails on a phone.
5. **Dedupe** — do not postpone, it pays off within a single mail.
6. **Hard filter** — must hit the 16.5 % from the simulation. A deviation is a bug.
7. **Enrichment** — HTTP fetch of the original ad, rate limit, cache, `robots.txt`.
   A failed fetch is not a knockout; the offer stays in as *incomplete*.
8. **Scoring + digest** — first daily overview, still without a frontend.
9. **Packaging** — cover letter from a Freemarker template plus reference projects from
   the profile, copy in the PDF matching the ad's language.
10. **Frontend** — shortlist, detail, pipeline, sources, profile & rules.

## Traps that have already cost money

- Search terms are wrapped in `<mark>` inside the title on some sources. Strip before any
  title comparison, or deduplication trips over `<mark>DevOps</mark>`. Not present in the
  current sample corpus; jsoup's `text()` handles it either way.
- Strip `(m/w/d)`, `(w/m/d)`, `(m/f/d)` before normalizing. Every title comparison goes
  through `TitleNormalizer`, so two of them cannot disagree.
- The location sits behind a `📍` prefix in one of four `span`s in `div.job-meta` —
  address it by the prefix, never by position.
- **Renaming the root component's selector means editing `src/index.html` too.** The
  Angular CLI generates `<app-root>`; the repo prefix is `lg-`. Every unit test still
  passes with the mismatch, because `TestBed` creates the component itself — the only
  symptom is a blank page in the browser, with no console error. Found exactly that way
  in step 1, so: verify a UI change in a real browser, not only in the suite.

## Open

- **CI.** The tooling baseline is in place (`.editorconfig`, ESLint, Prettier,
  Stylelint), but no pipeline runs it yet.
- **The Java package is `de.codeministry.leadgen`** — decided before the repository name
  and organisation were, so it may need a rename.
- License for publication (Apache 2.0, like `straightmail`?)
- Repository name and GitHub organisation
- Which folder in the IMAP mailbox the newsletter lands in
