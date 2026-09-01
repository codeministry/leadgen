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

- **Nothing is wired in.** This repo is going public. No newsletter name, no portal, no
  mail provider, no model name and no personal datum belongs in a committed file. The
  configuration that ships names every value as a `${PLACEHOLDER}`; the values live in
  `.env`, and anything individual beyond them in `config/`. Both gitignored.
  A new source is a YAML block, not a deploy.
- **Rules before model.** The hard filter runs deterministically and for free before any
  LLM call. Without a language model the tool must still run, only weaker.
- **No CV tailoring.** Fixed PDFs in `config/documents/`, selected by the language
  of the ad and nothing else.
- **Nothing is ever sent.** Both outputs are rendered files: the digest as text or HTML,
  the application package as a folder. There is no transport, no recipient and no channel
  in the configuration either — modelling one would be an invitation to add the code.
- **Two configuration layers, the same as Spring's own.** Working defaults ship on the
  classpath under `backend/src/main/resources/leadgen/` and are part of the jar; the
  directory in `leadgen.config-dir` overrides them **file by file**. The tool therefore
  runs on a fresh clone with no configuration at all, and nothing individual is ever baked
  into the artifact. The startup log names, per file, which layer won.
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
- **`.css`, never `.scss`** — Tailwind 4 is CSS-first. `color-no-hex` is on globally and
  `src/styles.css` is the **only** exempt file, so every colour literal lives there beside
  the two DaisyUI themes; `src/styles/tokens.css` holds semantic aliases (`--score-*`),
  layout constants and the type scale, and references `var()` only.
- **NgRx**: `@ngrx/signals` events dialect, stores as a `*.store.ts` + `*.events.ts`
  pair with `withReducer` + `withEventHandlers`. Model: `core/store/status.store.ts`.
  Where the I/O is the DOM rather than HTTP, `withHooks` + an `effect` replaces
  `withEventHandlers` — see `core/theme/theme.store.ts`.
- **Specs live beside their file.**
- **Strict TypeScript** plus `strictTemplates`, `noPropertyAccessFromIndexSignature`,
  `noImplicitReturns`, `noImplicitOverride`, `noUnusedLocals`. No `baseUrl` — TypeScript
  6 deprecates it and the path mappings resolve relative to `tsconfig.json` anyway.

## The design system

- **Two themes, `lg-light` and `lg-dark`,** declared as `@plugin "daisyui/theme"` blocks in
  `src/styles.css` with DaisyUI's own built-ins switched off (`themes: false`) so they do
  not ship dead. Palette **Petrol & Ocker**, all values OKLCH. Light primary is petrol
  `#0E6E6B`; dark primary is the logo's own cyan `#33E3DA`
  (`oklch(83.04% 0.1348 189.53)`, 10.5:1 on `base-100`), which is also what the "LEAD"
  half of the wordmark takes.
- **Ochre means one thing: this survived the filter.** Petrol carries structure and
  interaction, everything discarded is muted. Nothing else may take the accent. The score
  bands follow it — `--score-strong` ochre, `--score-weak` **secondary**, `--score-out`
  muted. Review takes secondary rather than primary because the dark primary is the
  logo's bright cyan and would outshine the ochre; the middle band must never be the
  loudest thing on the screen.
- **`--color-accent` is a fill and a large-number colour, never body text.** It is 3.05:1
  on the sand page and fails AA below 24 px. `--lg-accent-text` is the text variant at
  4.68:1, and `--lg-muted` / `--lg-warning-text` exist for the same reason.
- **The header carries `data-theme="lg-dark"` in both themes.** The logo asset has its own
  bright cyan, which is 1.6:1 on white and cannot be a light-theme colour at any size, so
  the lockup keeps a navy ground everywhere and stays identical in both modes. DaisyUI's
  themes are attribute-scoped, so nesting `data-theme` on an element re-declares every
  token for that subtree — the buttons, the muted version string and the two-tone wordmark
  inside the header all follow with no override anywhere. The `--lg-*` corrective tokens
  are declared on the same selectors and re-scope with it.
- **`system` is the absence of `data-theme`.** DaisyUI emits `lg-dark` under
  `:root:not([data-theme])` inside a `prefers-color-scheme` query, so removing the
  attribute *is* "follow the operating system". An inline script in `src/index.html`
  applies the stored preference before first paint and shares the `lg-theme` key with
  `core/theme/theme.model.ts`.
- **Fonts are self-hosted through `@fontsource-variable`,** never a CDN: Bricolage
  Grotesque (display, `opsz.css` for the wght + opsz axes), Manrope (body), JetBrains Mono
  (anything compared down a column). The rule is: a number you compare is mono, a number
  you admire is display. The twelve `type-*` utilities in `tokens.css` are the scale.
- **Icons go through `<lg-icon>`,** which renders `lucide`'s icon node arrays directly.
  `lucide-angular` pins `@angular/core: 13.x - 21.x` and cannot be used on Angular 22.
  Each icon is a named import in `shared/icon/lucide-icons.ts` so esbuild can tree-shake.
- **The brand mark is the real asset.** `shared/brand-mark/` renders
  `public/logo-mark.png` beside the two-tone LEADgen wordmark. `logo-mark.png` is
  `logo-1.png` cut out, trimmed and resized to 128 px tall — four times the 26 px the
  header shows. `favicon.ico` and `favicon-256.png` come from the same source on a round
  plate, so the tab icon and the header show one funnel; there the spout takes the accent
  and the plate is `#0E2C2D`, `base-200` pulled towards the petrol primary rather than the
  theme's near-black, because at 16 px the plate's hue is what carries the brand.
  `frontend/tools/build-favicon.sh` is the only thing that knows the spout's pixel box, so
  the icons are regenerated, never hand-edited. `logo-1.png` and `logo-2.png`
  stay as the untouched sources. The asset carries its own cyan and does not follow the
  theme; it sits on `base-100` in both, where it stays legible.

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
  `/config`, not `./config/local`. Such a path is still accepted, resolved from the working
  directory upwards, because breaking an existing configuration over a style is not worth it,
  **and the fallback logs a warning naming both paths**: it can resolve to a file outside the
  directory the process was pointed at, which turns two configurations into one and looks
  entirely normal doing it.
- **The working directory is not one thing.** Gradle's `bootRun` runs in `backend/`, an IDE
  run configuration in the repository root, a jar wherever it sits. A relative
  `leadgen.config-dir` is therefore searched upwards from the working directory; a default
  that is correct in one of them is wrong in the others, and the symptom is a missing file
  at a path nobody recognises.
- **Change detection polls timestamps, it does not use `WatchService`.** For three files
  the efficiency argument is worth nothing, and the watch service is native only on
  Linux; on macOS the JDK falls back to polling with a ten-second default latency. A
  change is applied one cycle after it is first seen, so a save in progress finishes
  first.
- **`application.yaml` is Spring's and only Spring's.** The tool's own configuration is
  `pipeline.yaml`, and the class behind it is `PipelineConfig`. They used to share a name,
  which meant a stack trace naming it could mean either file.
- **The classpath directory is `/leadgen/` and deliberately not `/config/`.** Spring scans
  `classpath:/config/` for its own configuration by default, so a file placed there would
  be read twice — once by this loader and once by Spring, which would quietly bind whatever
  happened to match.
- **A path in `pipeline.yaml` names a file, never a location.** Only the file name is used,
  and the two-layer lookup decides where it comes from. Anything more forgiving was
  measured and removed: resolving `config/local/matching-rules.yaml` from the working
  directory upwards made a run read a file from outside the directory it was pointed at,
  and look entirely normal doing it.

## Ingest and extraction

`backend/…/ingest/`. A connector fetches documents, `HtmlBlockExtractor` splits them into
blocks and reads fields, `OfferMapper` turns a block into an `ExtractedOffer`, `OfferStore`
upserts it. `POST /api/ingest` runs one pass.

- **No selector is written in Java.** Block selector, every field, the date format and the
  proxy parameter all come from the source's `extraction` section. That is what makes a
  new source a YAML block. The worked example is `local-eml` in
  `backend/src/main/resources/leadgen/sources.yaml`.
- **The eight field names are the contract** between `sources.yaml` and `OfferMapper`:
  `title`, `url`, `description`, `location`, `portal`, `agency`, `published`, `tags`. A
  field spelled differently is extracted and then ignored, in silence.
- **`expect_count_from_subject` is the only check nothing else can make.** A selector that
  stops matching loses offers, and fewer offers is indistinguishable from a quiet day on the
  market. The document states its own count; a mismatch is logged loudly and never discards
  what did come through.
- **A second source inherits an extraction, it never copies one.** `extraction.inherit: <id>`
  resolves at load, one level only. Two copies of a selector table drift, and the copy nobody
  looks at drifts unnoticed.
- **`prefer_part` picks the alternative, and the search runs backwards.**
  `multipart/alternative` orders its parts least-preferred first, so the plain-text version
  comes before the HTML one — taking part zero yields text with none of the structure the
  rules address.
- **A field's `format` describes the whole value, not a prefix of it.** The source-level
  `date_format` is the fallback. Cutting the raw string to the pattern's length works only
  while the two happen to line up, and stops at the first quoted literal.
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
- **Not flagging `\Seen` takes two things, and the obvious one alone is not enough.**
  The folder is opened read-only *and* `mail.imap.peek` is set. Fetching a body otherwise
  issues `FETCH BODY[]`, and the server sets the flag no matter how the folder was opened.
  Measured against a real IMAP server: without the flag every mail a run touches is marked
  read in the owner's mailbox.
- **`getMessagesByUID(start, LASTUID)` lies about its range.** It returns the message with
  the highest UID even when that UID is below `start`, so a mailbox with nothing new hands
  back its newest mail as if it were unread. Filter by UID afterwards, or every run
  re-extracts the last mail forever — which the upsert would hide.
- **A changed `UIDVALIDITY` voids every UID the server ever handed out.** The folder was
  recreated; a cursor kept across it silently skips the whole folder.
- **The cursor is advanced after the write, never after the read.** `SourceConnector.commit`
  exists for exactly that: a cursor moved at read time plus a failure afterwards means those
  mails are never looked at again, and nothing says so. And it advances only over messages
  actually processed — a mail the selector skipped is not progress, because a filter that
  turns out too narrow is fixed by widening it.
- **One failing source must not end the run.** `IngestService` catches `IngestException` per
  source, so an unreachable mailbox does not stop the file sources behind it.
- **The `<mark>` trap is not reproducible in the current corpus** — zero occurrences in all
  14 mails. jsoup's `text()` strips it regardless, and `ExtractionTest` guards it, but treat
  it as an expectation rather than a measurement.

## Deduplication

`backend/…/dedupe/`. One pass after every ingest run, over every offer inside
`deduplication.ttl_days`.

- **This is not the upsert in `OfferStore`.** That one collapses a *listing* seen twice,
  which is what re-reading a newsletter produces. This one collapses a *project* several
  portals advertise at once, which is 12.3 % of the measured corpus.
- **The fingerprint is the normalized title and nothing else, and that is measured.** The
  configured field list names `city`, `start_date`, `duration_months` and `top_skills`;
  all four come from enrichment, which runs *after* this stage. Adding the one field that
  does exist — the stated location — collapses 111 instead of 159, and the 48 it gives up
  are overwhelmingly correct merges lost to the same ad writing "Nürnberg" in one portal
  and "Remote und Nürnberg" in the next. A location must be parsed before it can be
  compared. **A field that is present is not the same as a field that is comparable.**
- **The consequence is accepted, not hidden:** two genuinely different projects that share
  a title do merge. ISC-40 states the limit rather than claiming the opposite.
- **It runs after every source, never per source.** A pass scoped to one source would
  never see the pair it exists to collapse.
- **Idempotent by construction.** The primary of a group is recomputed from the group
  every run — `first_value(id) OVER (PARTITION BY fingerprint ORDER BY ingested_at, id)` —
  so a second run assigns exactly what the first did and a listing arriving later attaches
  to the primary already there instead of starting a rival cluster. The update is
  restricted to rows whose assignment actually changes, which is what makes the "moved"
  count mean moved rather than seen.
- **`IngestReport.merged` is the standing total, not the rows this run moved.** A second
  run moves nothing, and a zero there would read as "deduplication stopped working".
- **Only `exact_fingerprint` is implemented.** The two embedding strategies need a model
  and are logged and skipped; failing at load would break the shipped defaults, and
  running silently would suggest a similarity pass happened. A `merge_policy` other than
  `keep_first_seen_as_primary` *is* fatal at load, because that one would be read,
  ignored, and quietly do the first-seen thing anyway.

## The hard filter

`backend/…/filter/`. Seven stages in a fixed order, applied after deduplication, with no
model and no network. It removes four offers in five for free, and only what survives
costs a language-model call.

- **Not one keyword is written in Java.** The lists come from `matching-rules.yaml` and
  the core skills from `skill-profile.yaml` — the same reason no CSS selector is written
  in Java. `docs/samples/simulate_filter.py` mirrors them and ISC-41 proves the two still
  agree over the corpus.
- **The order is the meaning.** abroad → remote share → out of reach → role or stack → no
  core skill → contract form → stale. An offer stops at the first rejection, which is the
  only reason the per-stage counts sum to the total (ISC-42).
- **The rate rule is deliberately absent.** It is configured `apply_after: enrichment` and
  the loader refuses any other value, because the sources state a rate in 0.0 % of offers.
- **Fold, then match on word boundaries.** `TextFold` is the one place text and patterns
  are normalised, and it exists because the reference got this wrong three separate ways:
  an umlaut fold that leaves `ko ln` and loses every Köln and Düsseldorf offer; substring
  matching where `ch` rejects Aachen and `ANÜ` hits Planung; and unfolded patterns
  compared against folded text, where `.net` and `c#` match nothing at all. All three were
  silent and all three moved the survivor count by hundreds.
- **`onsite_cities` is a list, not a radius.** An offer states its location as free text —
  "Remote und Nürnberg", "DE 7XXXX" — so a kilometre figure would need a dataset, a parser
  and a network call this stage must not need. A `onsite_max_km` key used to sit in the
  schema and nothing read it. An empty list is logged at load: it means only remote offers
  can pass, which otherwise looks exactly like a quiet market.
- **`role.rejected_title_keywords` is not `anti_skills`.** The latter is documented as a
  scoring penalty worth -30; reading it as a knockout as well would mean tuning the score
  silently changes what reaches the shortlist. The lists differ too — this one rejects
  roles, not only stacks.
- **Core skills are read with their aliases.** An ad asking for "Springboot", "Spring
  Data" or "k8s" names a core skill, and eight bare names would answer no. Worth twelve
  offers over the corpus.
- **The verdict is written on the offer**, stage and reason both. A rejection without its
  reason is a number nobody trusts a week later.

## Enrichment

`backend/…/enrich/`. The only stage that leaves the machine, run after the hard filter
and only on what survived — fetching a thousand ads to then discard eight hundred would
be rude to the portals and slow for nothing.

- **It never discards.** A fetch that is forbidden, rate-limited, unreachable or
  unreadable leaves the offer in the pipeline with a note saying why. Scoring then judges
  an incomplete offer as incomplete, which someone can review; an offer that quietly
  stopped existing cannot be.
- **Four gates, cheapest first:** cache, `robots.txt`, rate limit, network. A cached page
  costs nothing and consumes no rate-limit token, which is what makes a daily run one
  request per ad per week instead of one per ad per day.
- **Failures are cached, timeouts are not.** A 403 or a disallowed path is a fact about
  the page; a timeout is a fact about the moment, and remembering one bad minute for a
  week is worse than asking again tomorrow.
- **A cached failure reports itself as cached.** `FetchResult.cachedFailure` exists
  because the interesting property of a cached result is not that it failed but that *no
  request was made* — a cached 403 reporting itself as fresh makes the request count a lie.
- **The rate limit is a sliding window.** Twenty a minute has to mean twenty in any sixty
  seconds, not twenty at the top of each minute and forty across the boundary.
- **An unreachable `robots.txt` means allowed.** That is the convention, and the
  alternative is worse: a host whose robots.txt times out would silently stop being
  enriched and its offers would look merely incomplete.
- **No selector and no pattern is written in Java.** `enrichment.extract.fields` is a
  field-to-rule map in YAML, exactly like `sources.yaml`, because every portal renders an
  ad differently and a new one has to be a block and not a release. The seven field names
  — `rate`, `duration`, `workload`, `remote_percent`, `start_date`, `contact`,
  `full_text` — are the contract; a field spelled differently is read and then ignored, in
  silence. `strategy: readability` used to sit in the schema with nothing implementing it.
- **Regexes in YAML need single quotes.** A double-quoted scalar only allows a fixed set
  of escapes, and `\-` is not among them; the file fails to parse with "while scanning a
  double-quoted scalar" and nothing points at the regex. Single quotes pass backslashes
  through untouched.
- **Every enriched column is nullable and null means "not stated", never zero.** The whole
  reason this stage exists is that the newsletter states a rate in 0.0 % of offers, so a
  missing value has to stay distinguishable from a low one.
- **The page cache lives in Postgres.** The TTL is a week, the container has no volume for
  a scratch directory, and a cache that does not survive a restart turns a rate limit into
  a promise nobody keeps.

## Backend conventions

- **Lombok for the boilerplate, records for the data.** `@Slf4j` instead of a hand-written
  logger, `@RequiredArgsConstructor` where the constructor is nothing but assignments. Not
  where it does work (`ConfigRegistry` loads, `IngestService` builds a map) and not where
  the parameters carry annotations (`@Value` in `StatusController`) — Lombok would generate
  a constructor without them.
- **API types are records, each in its own file.** `AppStatus`, `IngestReport`,
  `SourceIngestResult`, `DocumentIngestResult`. No response type nested inside its
  controller or service. The configuration model is the exception: those records mirror the
  nesting of a YAML file, and flattening them would lose exactly the structure they exist to
  describe.
- **JDBC, not JPA.** The pipeline writes offers in batches and upserts them with
  `ON CONFLICT`, which is one statement of plain SQL against a schema Flyway owns. An ORM
  would add a mapping layer over Postgres arrays for no gain. Flyway is therefore the only
  thing that touches the schema at all.
- **Boot 4 split the integrations into their own modules.** Without
  `spring-boot-flyway` the migrations sit on the classpath and never run, and the only
  symptom is Hibernate complaining about missing tables. `@WebMvcTest` likewise moved
  from `…test.autoconfigure.web.servlet` into `spring-boot-webmvc-test`.
- **The credentials file is `.env` and cannot be called anything else without a cost.**
  Compose substitutes the `${...}` in `docker-compose.yml` from `.env` and nothing else:
  not from `env_file:`, which only injects into a container, and not from
  `COMPOSE_ENV_FILES` set inside a file (measured — real environment variable or
  `--env-file` only). Another name needs a flag on every call or a symlink, and forgetting
  either silently applies the compose defaults, so the stack listens where the application
  is not looking.
- **A published port's container side is fixed at 5432.** Postgres binds that port inside
  the container whatever the host side is; making both sides variable publishes a host port
  forwarding to a port nobody listens on, which looks exactly like no port at all.
- **The database host port defaults to 55432, not 5432.** A developer machine usually
  already has a Postgres on 5432, and connecting to the wrong one fails as
  `password authentication failed for user "leadgen"` — a message naming the user and
  neither the host nor the database it actually reached. `DatasourceBanner` prints the
  effective JDBC URL at startup for the same reason the frontend prints its proxy target.
- **`.env` is read by `PlaceholderResolver`, not by the build.** It used to be a
  `bootRun` hook, so launching the very same configuration from an IDE silently saw none of
  it: the value was in the file and the service said it was missing. The file is searched
  upwards from the working directory and real environment variables win, so every start path
  behaves identically. Compose reads the same file.

## What already exists

```
backend/src/main/resources/leadgen/    the committed defaults — neutral, all values as
  pipeline.yaml                        ${PLACEHOLDERS}. These ARE the examples; there is
  matching-rules.yaml                  no second copy to drift.
  sources.yaml
  skill-profile.yaml
config/*.yaml                     the same four names, overriding file by file (gitignored)
.env.example
docs/samples/emails/*.eml         14 real newsletter mails (gitignored)
docs/samples/analyze_samples.py   extraction, field coverage, duplicates
docs/samples/simulate_filter.py   simulation of the hard filters

frontend/src/styles.css           both DaisyUI themes, the fonts, the @theme block —
                                  the only file allowed to hold a colour literal
frontend/src/styles/tokens.css    semantic aliases, layout constants, the type scale
frontend/src/app/core/            api seams, stores, models, fixtures, theme, shell
frontend/src/app/layout/          shell, header, nav rail, theme toggle
frontend/src/app/shared/          icon, brand mark, score, funnel rail, badge, stat tile,
                                  empty state, page header
frontend/src/app/features/        dashboard, shortlist (+ offer card), offer detail,
                                  pipeline, sources, rules
frontend/tools/build-favicon.sh   renders favicon.ico, favicon-256.png and logo-mark.png
```

The two Python scripts are the **reference implementation**. Whatever they do, the Java
code has to reproduce — the numbers in `docs/SAMPLE-ANALYSIS.md` are the target values.

## Measured baseline

- 14 mails, **1289 offers**, all extracted deterministically via CSS. The count announced
  in the subject matches exactly in all 14. `fallback: none` for this source.
- **0.0 % contain an hourly rate.** Rate, duration, workload and start date only arrive
  from the enrichment stage (fetching the original ad from the portal).
- The hard filter lets **18.5 %** through — 239 of 1289 — and **~16 per mail** after
  deduplication. That is the daily LLM budget. The stages and the three defects that
  moved this number are in `docs/SAMPLE-ANALYSIS.md` § 5.
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
4. ✅ **IMAP connector** — same extraction, different source. Progress tracked via
   `UIDVALIDITY`/`UID`, **never** via seen/unseen: the user reads the same mails on a phone.
5. ✅ **Dedupe** — `DeduplicationService` clusters after every ingest run, globally rather
   than per source, because the whole point is one project reaching the pipeline through
   several portals. One SQL statement, idempotent by construction.
6. ✅ **Hard filter** — seven stages, every list from configuration or the profile, no
   model and no network. Reproduces `docs/samples/simulate_filter.py` exactly: 239 of
   1289, 18.5 %, and the per-stage counts.
7. ✅ **Enrichment** — HTTP fetch of the original ad, rate limit, cache, `robots.txt`.
   A failed fetch is not a knockout; the offer stays in as *incomplete*.
8. **Scoring + digest** — first daily overview, still without a frontend.
9. **Packaging** — cover letter from a Freemarker template plus reference projects from
   the profile, copy in the PDF matching the ad's language.
10. 🟡 **Frontend** — design system, shell and all six screens exist. The dashboard runs
    on the real `GET /api/status` and `POST /api/ingest`; shortlist, offer detail,
    pipeline, sources and rules run on `core/fixtures/`, every file marked
    `// FIXTURE — replace with the real endpoint`. Each feature reads through an `Api`
    seam (`core/api/shortlist.api.ts` is the model), so swapping in HTTP is one file per
    feature. Steps 5 to 9 are what unblocks that.
11. **Manual status capture** — the tool never sends. It finds, filters, scores and
    packages; Marcello sends the mail himself and therefore records the outcome himself.
    The pipeline board is the only place that state exists, so it needs
    `PATCH /api/applications/{id}` plus an `application` table, a status control on the
    board and on the offer detail, and the `sent_on` / `follow_up_on` dates the
    dashboard's follow-up tile counts. Nothing in the system can observe the result of a
    mail it did not send.
12. **Manual entry** — an offer found by hand must be able to enter the pipeline, or the
    shortlist quietly stops being the whole picture. A Markdown file uploaded on the
    Sources screen lands in `<config-dir>/inbox/` and is read by a `manual-inbox` **file**
    source on the next run, so no new connector is needed. One document is one offer here,
    so it needs a `markdown-frontmatter` extraction strategy: YAML frontmatter carries the
    eight-field contract, the body is the description, `fallback: llm` covers a raw pasted
    ad. `external_id` is the unwrapped URL or a content hash, otherwise re-uploading the
    same ad makes a second offer. `ProxyLink.unwrap` still applies, and the inbox is
    gitignored. `POST /api/sources/manual/documents` is the first write endpoint in this
    app and writes to disk, so it needs an extension allowlist, a size limit, a sanitised
    filename and a decision about `security.auth`, which is `none` today.

    **An upload is reviewed before it becomes an offer, and the file stays the record.**
    A pasted ad has no guaranteed frontmatter and the LLM fallback can read it wrong, so
    a bad extraction would otherwise enter the shortlist silently — the one place this
    tool cannot afford to be quietly wrong, because the shortlist is what gets trusted
    instead of the mailbox. The upload therefore lands in `inbox/pending/`, which no
    source globs; a review screen shows the extracted eight fields beside the source
    text, says whether the offer is already in the pipeline (deduplication answers that
    before the confirm, not after), and lets the fields be corrected. Confirming writes
    the corrected frontmatter back into the file and moves it to `inbox/`, where the
    `manual-inbox` source picks it up on the next run. No staging table: the file is the
    state, it is inspectable with `cat`, and a rejected upload is a file that was
    deleted rather than a row nobody will ever look at.

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
- **A component class name must not collide with a DaisyUI component class.** DaisyUI 5
  ships `status` (a 0.5 rem dot) and `label`, among others. A header span classed
  `.status` was laid out as an 8 px box with its text overflowing under the next control,
  and nothing reported a problem. Check a new class name against DaisyUI's component list,
  or prefix it.
- **Tailwind 4 scans source *text* for class names, so a class assembled at runtime is
  never emitted.** `'badge-' + tone()` left seven of eight badge tones with no colour at
  all; measured against the built stylesheet, only `badge-accent` and `badge-outline`
  existed. Spell every variant out in a literal lookup map, or the class exists only in
  the DOM and never in the CSS.
- **Router input binding writes `undefined` for an absent query parameter**, overriding
  the input's declared default. The first `q().trim()` on it throws inside the template
  and leaves the page half-rendered — the page title empty, half the controls gone, and
  nothing in the console pointing anywhere near the cause. Every routed input needs
  `transform: (value) => value ?? <default>`.
- **`min-height: 100%` breaks at `<lg-root>`,** which has no height of its own, so the
  percentage chain has nothing to resolve against and the nav rail ended at its last menu
  item. `100dvh` has no such dependency.
- **The flex item is the component host, not the element inside it.** Styling `.rail`
  without `:host { display: flex }` leaves the host at its inline default, and the child
  never stretches.
- **A bare boolean attribute binds as the empty string** (`outline`, not
  `[outline]="true"`), which `strictTemplates` rejects — but only at build time.
  `tsc -p tsconfig.app.json` in `check:static` does not run the Angular template compiler,
  so `bun run test` or `bun run build` is the gate that catches template type errors.
  Passing `check:static` says nothing about the templates.
- **A backgrounded tab suspends CSS transitions,** and `getComputedStyle` then returns the
  transition's *start* value rather than its target. An active nav link read as muted grey
  while being correct in a real browser. Anything transitioned, animated, or driven by
  `ResizeObserver`/`IntersectionObserver` must be measured through the Interceptor skill's
  `Tools/VerifyViewport.ts`, never through a background tab.
- **A CSS transition never fires on first paint.** A width rendered correctly the first
  time never changes, so nothing animates and the reveal silently does not exist. It needs
  two states: render the start value, let the browser paint it, then set the target —
  `afterNextRender` plus one `requestAnimationFrame`. See `shared/funnel-rail/`.
- **`repeat(auto-fit, minmax(21rem, 1fr))` cannot shrink below its minimum**, so a panel
  grid pushed the page sideways at 320 px while looking fine everywhere else. Write
  `minmax(min(21rem, 100%), 1fr)`. In the same family: a flex item will not go below its
  content width without `min-width: 0`, which is what let a 20 rem search input overflow a
  320 px screen.
- **Test horizontal overflow at the page, not at the element.** Comparing every element's
  right edge against the viewport flags the kanban board and the wide tables, which scroll
  inside their own `overflow-x: auto` on purpose. The real check is
  `document.scrollWidth > document.clientWidth`.
- **ImageMagick renders SVG with its own parser and drops paths containing arcs** unless
  `rsvg-convert` is on PATH as its delegate. The first favicon looked broken for that
  reason alone, with the geometry perfectly correct.

## Open

- **CI.** The tooling baseline is in place (`.editorconfig`, ESLint, Prettier,
  Stylelint), but no pipeline runs it yet.
- **The Java package is `de.codeministry.leadgen`** — decided before the repository name
  and organisation were, so it may need a rename.
- License for publication (Apache 2.0?)
- Repository name and GitHub organisation
- Which folder in the IMAP mailbox the newsletter lands in
