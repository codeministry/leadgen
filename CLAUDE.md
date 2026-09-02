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
  mail provider, no model name and no personal datum belongs in a committed file. The rule is
  about **values**, not dependencies: `build.gradle.kts` names two model vendors because a
  starter is a library, and `base_url` still decides who actually answers. The
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
- **Never commit.** Do the work, leave it uncommitted, offer the commit — the maintainer
  reviews the diff and decides what lands.

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

Carried over from a sibling Angular project, which is the house style:

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

## The interface language

`frontend/src/app/core/i18n/` plus the two catalogs in `frontend/public/i18n/`. Transloco,
two languages, English the fallback.

- **English is the fallback because English is this repository's language.** A key nobody
  translated shows the sentence that was written, never a blank — and Transloco's own
  missing handler returns the key, so a *server* sentence passed through the pipe renders
  as itself. That is what lets the plain-text reason behind a rejected upload keep working.
- **`system` is a state, not the absence of one**, exactly as in the theme store: it
  resolves to the browser's language and the toggle still shows `system` as chosen. The
  same shape (`withHooks` + an `effect` on the resolved value) for the same reason — the
  I/O is the DOM and localStorage, not HTTP.
- **No prose is written in TypeScript.** A nav item, a band filter and a field row carry a
  catalog key; the template pipes it. A sentence assembled in a component (`review`'s
  summary, the dashboard's share) returns a key and its parameters instead, because the
  number sits in a different place in every language.
- **Plurals are ICU, through `transloco-messageformat`.** "1 listings" is the kind of wrong
  that only appears on the one day a run finds exactly one, and German declines
  differently — a rule per language belongs in the catalog, not in a ternary.
- **The catalogs are static files under `public/i18n/`, not bundled.** A translation fixed
  at five in the afternoon should not need a rebuild to reach the browser.
- **Transloco is provided globally in the tests** (`src/test-setup.ts`, with the real
  English catalog). Without it a spec fails with `No provider found for
  TRANSLOCO_TRANSPILER` from inside a component that has nothing to do with i18n, and a
  stub catalog would let the templates and the catalog drift apart unnoticed.
- **The server's own prose is not translated and this is the boundary.** Filter-stage
  descriptions, lane labels, knockout labels and every score reason arrive as English
  sentences from the API and stay English in both languages. Translating them means the API
  handing over an id and the browser holding a catalog keyed by it — which is the one thing
  the read side deliberately does not do today.

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
- **`text()` joins every node with a space, so an advert arrives as one line — and keeping
  only the block boundaries is not enough either.** Paragraphs come back and the headings,
  the bullet lists and the emphasis stay gone, which on screen is a column of equal-looking
  paragraphs: nearly the wall it replaced. `HtmlToMarkdown` converts the element to
  Markdown instead, which keeps all of it and is still plain text, so the filter still
  matches words and a reader with no renderer still sees the ad.
- **Only the prose field is read that way, and it is named rather than inferred.** The
  markup does not say which field is a document: a title sits in an `<h3>` on the sample
  source and would arrive as "### Senior Java Developer" — in the shortlist, in the
  fingerprint and in the cover letter. `description` in ingest and `full_text` in
  enrichment are the two, and the eight field names were already the contract.
- **A pattern still reads the collapsed text.** A regex in YAML is written against a line,
  `.` does not match a newline, and `**` around a word breaks it outright. **A pattern
  reads a line, a field reads a document.**
- **Links are resolved against the page before conversion.** A portal writes
  `/projects/argo-cd`, and a relative link surviving into the Markdown is a link into *this*
  application's router, which answers it with the shortlist. Without a base URI the target
  is dropped and the text kept, which is the right way round.
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
- **The IMAP side is Spring Integration's `ImapMailReceiver`, used with no channel and no
  poller.** A run is a synchronous pull that has to come back with per-source counts, and an
  inbound channel adapter has nothing to hand back. Three of its behaviours are documented
  nowhere near the setter that causes them, and each yields zero documents from an intact
  mailbox: it resolves `integrationEvaluationContext` on init, so `spring-integration-mail`
  alone fails with "No such bean" without `spring-boot-starter-integration`; with
  `autoCloseFolder` on it closes the folder before a body can be read, so every message ends
  in `FolderClosedException`; and with it **off** `receive()` hands back Spring messages
  rather than `jakarta.mail` ones, which an `instanceof` check silently drops.
- **Not flagging `\Seen` still takes two things.** `shouldMarkMessagesAsRead(false)` is not
  enough on its own: fetching a body otherwise issues `FETCH BODY[]`, and the server sets the
  flag regardless. `mail.imap.peek` is the second. Measured against a real IMAP server.
- **Three guarantees were given up when the cursor went, and they are worth naming.** The
  receiver tracks what it has handed over with a *user flag* written into the mailbox, not
  with a UID watermark kept on this side. So the tool no longer leaves the owner's mailbox
  untouched; "a message the selector skipped is not progress" is gone, because the flag lands
  on everything the *search* returned before sender, subject and age are applied; and a
  recreated folder has no equivalent of the `UIDVALIDITY` reset. `flaggedAsFallback` is off,
  so a server without user flags gets no marker rather than a `\Flagged` the owner would see.
  What still holds: no `\Seen`, no `\Flagged`, no `\Deleted`.
- **`IngestCursor` and `IngestCursorStore` are read by nobody now**, and the `ingest_cursor`
  table is still there. Dead code of exactly the kind this repository removes elsewhere;
  left standing only because dropping the table is a migration and a decision.
- **One failing source must not end the run.** `IngestService` catches `IngestException` per
  source, so an unreachable mailbox does not stop the file sources behind it.
- **The `<mark>` trap is not reproducible in the current corpus** — zero occurrences in all
  14 mails. jsoup's `text()` strips it regardless, and `ExtractionTest` guards it, but treat
  it as an expectation rather than a measurement.

## Manual entry

`backend/…/manual/` plus the `markdown-frontmatter` strategy in `ingest/extract/`, and
`features/review/` in front of it. A `.md` file dropped in the inbox becomes an offer on
the next run; a file uploaded through the browser waits for review first.

- **It is a `file` source, not a new mechanism.** `manual-inbox` in the shipped
  `sources.yaml` points at a directory and reads `*.md`. An upload only has to put the
  file where that source is already looking, so copying one in by hand works with no UI at
  all — and there is no second code path to keep in step.
- **One document is one offer here**, unlike the newsletter where one document holds a
  hundred. So there is no block selector and no `expect_count_from_subject`; what earns
  this a strategy of its own is that it stays deterministic. An offer typed by hand needs
  no language model to be read, which keeps *rules before model* true on the one path a
  person walks by hand.
- **The frontmatter is the eight-field contract, and the body is the description.** The
  body wins over a `description:` key: someone who writes both means the prose they typed
  under the fence. A key spelled differently is read and then ignored, in silence, which is
  exactly why an upload has to be reviewed before it becomes an offer.
- **YAML resolves scalars, so everything is stringified before `OfferMapper` sees it.** A
  bare `2026-09-01` parses to a date, and `String.valueOf` on it yields a form no
  `date_format` describes.
- **`external_id` falls back to a hash of the title and the text.** The upsert is on
  `(source_id, external_id)`, so without it the same ad uploaded twice is two offers and
  deduplication has to clean up after. It is a weak identity, but it is the one the
  document itself carries, and re-reading has to stay free.
- **`ProxyLink.unwrap` still runs.** A file pasted out of the newsletter carries the
  subscriber's address in every link, and it does not matter that the document arrived by
  hand.
- **A relative source `path` resolves against the configuration directory**, not the
  working directory — `Directories.under`. The same rule the four YAML files follow.
  Against the working directory the very same configuration points at `backend/…` under
  `bootRun`, at the repository root in an IDE and at neither from a jar: three empty
  directories that all look like a source with nothing in it. Measured: a test run created
  `backend/config/inbox/pending` before the rule was applied, outside the gitignore that
  was written for `config/`. A path that only resolves from the working directory is still
  read, **and the fallback logs a warning naming both paths** — exactly as the config
  loader's does, and for the same reason: breaking an existing configuration over a style
  is not worth it, but a directory outside the one the process was pointed at must not be
  silent.
- **`pending/` is a subdirectory of the inbox and is inert by construction.** The file
  connector lists regular files only, so what waits for review cannot be ingested by
  accident.
- **A file with no frontmatter yields no offer.** That is the `fallback: llm` case and it
  is not implemented; the file stays where it is rather than entering as an offer with no
  title.

## The upload and its review

`backend/…/manual/ManualUploadService` and `web/ManualSourceController`, with
`features/review/` on the other end. The first endpoint that puts a file on disk.

- **An upload lands in `pending/` and becomes an offer only when somebody confirms it.**
  A pasted ad can be extracted wrongly and a frontmatter key spelled differently is read
  and then ignored, in silence. Without the step in between, a bad reading enters the
  shortlist, which is the one list that gets trusted instead of the mailbox.
- **No staging table: the file is the state.** It can be read with `cat`, confirming is a
  move, and a rejected upload is a file that was deleted rather than a row nobody looks at
  again. The correction is written back into the document, so re-reading the same file
  later produces the same offer.
- **`ManualDocumentName` is the whole attack surface, and it is one file.** `sanitize`
  decides what a name may contain, `resolve` decides where the result may land, and both
  run on every path. The second check is not redundant: a rule enforced only by
  construction stops being enforced the first time construction changes.
- **A directory part in an uploaded name is dropped, not cleaned.** A name is a name, and
  the only reason an upload carries a path is that someone wants it somewhere else.
  `../../etc/passwd.md` becomes `passwd.md`.
- **The extension list is an allowlist, and it is the source's glob.** Anything but `.md`
  is a file nothing would ever read again, so accepting it would only be a place to store
  things.
- **The size limit is checked twice on purpose.** `spring.servlet.multipart.max-file-size`
  belongs to the container and answers with a framework error; the explicit check belongs
  to the endpoint and answers with a sentence naming the limit.
- **Deduplication answers before the confirm, not after.** The same fingerprint the dedupe
  pass uses is looked up while there is still a decision to make, so adding something
  already in the pipeline costs nothing and says so.
- **The 400 carries its reason as plain text.** "only .md documents are accepted" is
  actionable; a bare 400 is a support request. The store shows the server's sentence rather
  than one of its own.
- **`security.auth` is answered rather than left open.** Only `none` is implemented, so any
  other value is now **fatal at load** — someone writing `basic` and believing the write
  endpoints are protected is the worst failure available here. What stands in front of them
  instead is `server.address`, which defaults to `127.0.0.1`; the container overrides it
  because a process bound to loopback inside one is reachable through nothing at all.
- **Uploading is not ingesting.** The file goes in the queue and *Run ingest* does the
  rest, so there is exactly one thing in this application that reads sources.
- **Punctuation does not belong around `@if`.** A count assembled as
  `{{ n }} waiting@if (…) { , … }.` renders with the template's own whitespace inside the
  sentence — "1 waiting for review , 1 already in the pipeline ." on the page. Build the
  sentence in TypeScript.

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

`backend/…/filter/`. Six stages in a fixed order, applied after deduplication, with no
model and no network. It removes four offers in five for free, and only what survives
costs a language-model call.

- **Not one keyword is written in Java.** The lists come from `matching-rules.yaml` and
  the core skills from `skill-profile.yaml` — the same reason no CSS selector is written
  in Java. `docs/samples/simulate_filter.py` mirrors them and ISC-41 proves the two still
  agree over the corpus.
- **The order is the meaning.** abroad → remote share → out of reach → role or stack → no
  core skill → contract form. An offer stops at the first rejection, which is the only
  reason the per-stage counts sum to the total (ISC-42).
- **Nothing here reads a date, and `STALE` used to be the seventh stage.** "Too old" is not
  a verdict about an advert — an old advert is a good advert nobody will answer any more —
  and a verdict is what the funnel reports and what somebody reads when they ask why an
  offer is missing. The rule kept its name and moved to the archive.
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
- **`remote.accept_unknown` is displayed and never read.** `RulesView` renders it, the
  config model validates it, and `HardFilter` consults it nowhere: an offer that states no
  remote share simply falls through to the next stage, which is what the flag describes but
  not because of it. Setting it to `false` changes nothing. Same class as the
  `onsite_max_km` key that sat in the schema with no reader — and worth keeping in mind
  before the flag is trusted in an argument about why an offer survived.
- **`min_remote_percent: 0` switches the reach rule off, and that is the point.** Zero
  required remote share means being on site is acceptable, and then it is acceptable
  anywhere — the hand-written city list stops applying. Without the condition the two
  settings pulled against each other: the share rule said "on site is fine" and the reach
  rule still rejected every town not on the list. Measured on the archive: 145 of 254
  offers died there while the share was already at zero, and not one of them stated a
  remote share at all. Above zero the list is back, because needing 40 % remote means being
  on site for the other 60 % and that part has to be somewhere reachable.
- **`accept_unknown` and `OUT_OF_REACH` still answer different questions.** An unstated
  share is not a rejection *for its share*; above a zero minimum the offer then meets the
  reach rule, which asks whether the location is near or the text says remote.
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
  seconds, not twenty at the top of each minute and forty across the boundary. That is also
  why it is not Resilience4j's: its `RateLimiter` resets permits at fixed cycle boundaries, so
  adopting it would be a documented regression rather than a simplification.
- **Retry is Framework 7's `RetryTemplate`, and it wraps the network call alone.** Two
  attempts with backoff, on a transport failure or a 5xx and never on a 4xx. Around `fetch`
  it would retry past the cache and past the rate limiter, spending tokens the limiter had
  already refused. `@Retryable` is not usable here: `AdFetcher` is built per run from the
  hot-reloadable settings, so there is no bean and no proxy.
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

## Scoring and the digest

`backend/…/score/` and `…/digest/`. Two halves and one file.

- **Rules before model, again.** `RuleScorer` decides everything the profile and the
  offer's own fields can decide — core-skill overlap with aliases, rate against the floor,
  seniority, how much of the engagement's shape is stated, industry — for free. A `Judge`
  is asked only about role fit and the three penalties.
- **Unscored is not zero, and not nothing.** With no key the deterministic reasons are
  still written, so the operator sees "+45 core skill overlap, +10 rate fit". What is
  withheld is the *total*: computed from five of the nine weights it would not be
  comparable to one from all nine, and the same offer would score differently depending on
  whether a key happened to be configured that morning.
- **The weight table decides, not the answer — and it is read, not restated.** A factor the
  model invents is dropped, and a model awarding itself 900 points for role fit gets exactly
  what `scoring.weights.role_fit` says. The four bounds used to be Java constants that
  matched the table by coincidence: raising a weight in the file moved the deterministic
  half of the score and left both the clamp and the prompt text where they were. The factor
  *names* stay in Java, because they are the judge's contract the way the eight field names
  are the extractor's; the numbers behind them come from the configuration, per run.
- **A judge that fails returns nothing rather than throwing.** One unreachable endpoint
  must not end the run; the offer keeps its deterministic reasons and scores lower, which
  is visible and reviewable.
- **`provider` is a kind, never a default.** It names a wire format and nothing else. Two
  are implemented — the OpenAI-compatible chat API (`openai-compatible`, `ollama`) and the
  Messages API (`anthropic`) — and the base URL still decides who answers. A provider the
  code does not know is refused loudly rather than approximated, because a request in the
  wrong shape does not fail cleanly: it comes back a 400, or gets parsed out of a field
  that is not there into an offer that looks judged and is not.
- **The wire format is Spring AI's problem now, and that is most of what it bought.** Five
  differences between the two APIs used to be spelled out by hand and each failed silently:
  the auth header, the version header, the system prompt as a field rather than a message, the
  mandatory `max_tokens`, and the answer in `content[]` rather than `choices[]`. Two of them
  had already cost a run — the current models reject a `temperature` outright, and reasoning
  counts against `max_tokens` before the text begins.
- **The answer is read out of every generation, not the first one.** Spring AI emits a model's
  thinking as a generation of its own, *ahead of* the text, so `call().content()` alone hands
  back the reasoning and drops the JSON. Four missing factors on an offer that looks judged,
  and the same trap the raw HTTP version documented, returned through the framework.
- **The *model* modules, never the `spring-ai-starter-model-*` ones.** The judge is built per
  run from the hot-reloadable snapshot, so there is nothing for auto-configuration to
  configure — and it is not merely useless: it builds every model the module knows at boot, so
  the OpenAI starter failed the whole context with "At least one credential source must be
  specified" while constructing an *audio speech* model this application will never call.
- **The batch path is still hand-rolled HTTP, deliberately.** Spring AI 2.0 has no batch
  abstraction; the SDK underneath has one only behind `client.beta()`, and taking it would
  rebuild the request as typed params and rewrite four JSONL tests to reach the same two
  endpoints that already work.
- **`base_url` is required even for a hosted provider whose address never changes.** A URL
  in the code is a vendor in the code, and no committed file in this repository names one.
  It lives in `.env` beside the key.
- **One judge, one question.** `ChatClientJudge` owns the question, the bounds, the
  description of an offer and the reading of the answer, for every provider. `AnthropicJudge`
  extends it and adds nothing but the batch half. The bounds especially: they are what stop a
  model outvoting the weight table, and a second copy would mean the same offer scoring
  differently depending on who was asked.
- **Ollama is the one provider that needs no key**, and requiring one made it unusable —
  there was nothing to write in `.env`, so the judge was silently never built. The rule is
  about the value, which is why the provider is listed separately from
  `openai-compatible` even though it gets the same judge.
- **Only `llm.models.scoring` is read.** `extraction` has no LLM fallback implemented, the
  cover letter is a Freemarker template, and `embedding` belongs to the two deduplication
  strategies that are logged and skipped. Three keys that look configured and are not is
  the same class of lie as an unimplemented auth mode, so the shipped file says so.
- **The judge is built per run**, not once at startup, because the configuration is
  hot-reloadable: a key added to `.env` should start producing scores without a restart.
- **A run judges what is stale, not everything that ever passed.** Every stage before this
  one already worked that way; scoring queried `status = 'PASSED'` alone, so each run paid
  a language-model call for the whole standing backlog and the bill grew with the accumulated
  list rather than with the day's inflow — silently, because a re-judged offer produces the
  same number as before. Three things make a score stale and they are the three it is only
  comparable within: never written, different `ruleset_version`, different `score_model`. The
  last is not caution about a worse model. Two judges are two scales, and the shortlist
  threshold is one number read against both.
- **Only `ScoringReport.scored` counts this run; the rest are standing totals.** The same
  reason `IngestReport.merged` is one. Once a run judges only what changed, a second run
  legitimately judges nothing, and per-run counts would report an empty shortlist rather
  than an idle pass.
- **The judge is a bounded classifier, so it does not need the largest model.** Four factors
  clamped to +15 / -30 / -25 / -10 by the weight table before anything is kept, and the
  answer is a few lines of JSON. `LLM_MODEL_SCORING` is a `.env` line, so which model
  answers is measured against `offer_score_reason` rather than argued about — and on models
  where thinking is on by default, the reasoning tokens are billed at the output rate for a
  classification that fits in three lines.
- **Batching is off by default, and it moves the end of the run.** `llm.batch` hands the
  scoring requests over as one batch at half the price; the answers arrive minutes later,
  so `ScoreBatchCollector` polls, writes them, and then runs packaging and the digest. The
  digest is still the last thing that happens, just not in the request that started it. Off
  by default because a run that answers within itself is the simpler thing to reason about
  and the saving is worth having only once the nightly pass is large.
- **`offer.score_batch_id` is what stops a batch being paid for twice.** The staleness guard
  asks what still needs judging, and an offer whose answer is bought and in flight does not.
  Without the pointer the next run resubmits it, and the symptom is a bill, not a bug. It is
  also why "what is in flight" survives a restart.
- **`llm.batch: true` on a provider with no batch endpoint is fatal at load.** Same class of
  lie as an unimplemented auth mode: read, ignored, and scoring synchronously at full price
  while the person who wrote it believes they are paying half. `PipelineConfig.Llm.BATCHING_PROVIDER`
  is the single name, so the loader and the judge factory cannot disagree.
- **A collected batch releases its offers whatever happened to it.** Ended, failed, or
  collected under a configuration that can no longer talk to it — all three clear the
  pointer. An offer held by a finished batch is held forever, and nothing says so.
- **An offer whose batch entry errored stays unscored rather than getting a partial total.**
  The same rule as the keyless path: five of nine weights do not make a number comparable to
  one from all nine. Written that way it is self-healing, because a null `score_model` makes
  the offer due again.
- **The bounds live in `ChatClientJudge.reasonsOf` and the clamp in `Score.of`, once each.** Two
  paths now produce one score, and a shortlist whose halves bound or clamp differently is
  not a ranking — the same offer would score differently depending on how busy the night was.
- **Which judge answers is a parameter of the run, not a setting.** `llm.models.scoring`
  is the default and `scoring_options` names the alternatives; the select beside the run
  button sends one of them with the request, and the server remembers nothing. A stored
  setting would mean a scheduled pass silently inheriting whatever the browser last showed,
  and the one thing worth comparing here is two models over the same corpus.
- **The configured list is an allowlist, and it is checked before the run starts.** The name
  arrives as a request parameter and the endpoint behind it is billed per token, so anything
  else is refused rather than forwarded — a model the provider happens to accept answers,
  scores, and writes itself into `score_model`, where it is indistinguishable from a
  deliberate choice. `Judges.check` runs as `IngestService.run`'s first statement because
  scoring is the last stage: checked only where it is used, an unknown name comes back 400
  having already read the sources, clustered the duplicates, applied the filter and fetched
  the surviving ads. Measured, before the check was moved.
- **Switching the model re-judges the standing shortlist, and that is the price of the
  comparison.** `score_model` is one of the three staleness criteria, so the choice is never
  free: one full pass at the chosen model's rate every time it changes. It is also why the
  choice cannot be a display preference — two judges are two scales, and the shortlist
  threshold is one number read against both.
- **The browser holds the choice in localStorage and drops one the server no longer offers.**
  The server refuses it anyway, but a name picked weeks ago and kept locally would otherwise
  turn the next click into a 400 for a reason nobody can see. Below 40rem the select is
  hidden rather than wrapped — measured: at 480 px the header wanted 555 — and hiding the
  control does not clear the setting.

- **The digest is a file, and the last thing a run does.** No transport, no recipient, no
  channel — and no schedule of its own either: whatever schedules the run schedules the
  digest, and a cron nothing reads would be one more key that lies. An unscored offer gets
  its own heading rather than being sorted to the bottom of a ranking that does not exist.

## The application package

`backend/…/packaging/`. One folder per offer above the shortlist threshold, built at the
end of a run.

- **This is where a send button would arrive**, one convenient afternoon: the folder is
  finished and the contact is right there in `meta.json`. `NothingIsSentTest` reads the
  repository for `Transport.send`, `JavaMailSender`, `MimeMessageHelper`, `setRecipient(`
  and `mailto:`, and for configuration keys naming a transport — in the backend and in the
  frontend both. ISC-52 is enforced, not remembered.
- **`new MimeMessage` is deliberately not on that list.** It is how an `.eml` file is
  parsed, and the file connector does exactly that. Neither is `channel:` a forbidden key:
  `sources.yaml` uses it for where an offer *came from*. A check that cannot tell inbound
  from outbound is a check that gets switched off.
- **Templates come from the two-layer lookup**, `templates/…` in the config directory
  first and on the classpath second, exactly like the four YAML files. `{lang}` in a
  template path is the language of the ad and nothing else.
- **Templates see camelCase.** The row from the database is snake_case, and
  `offer.full_text` resolves to nothing in Freemarker rather than failing — silently
  producing a letter with a hole in it. The model is converted once before rendering, and
  the Freemarker exception handler is set to rethrow for the same reason.
- **Language: German if the text is German, English if there is text and no German, the
  profile's `locale_primary` only when there is nothing to go on.** The order matters —
  falling back to `locale_primary` for an ad that simply has no German in it sends a
  German letter to an English posting. Measured: 0 of 1289 descriptions lack a German
  function word, so English really is the exception and not the default.
- **No CV is tailored.** The language picks a fixed PDF, and that is the whole rule. A
  missing file is recorded as `cv-MISSING.txt` rather than failing the package: without
  the CV it is still most of the work.
- **`meta.json` carries the decision, not just the offer** — the score, every reason
  behind it, the fields, the matched skills, the reference projects chosen, and every
  portal in the duplicate cluster, so one project advertised three times is one package
  that says so.

## The archive

`backend/…/archive/`. What is no longer on the working list, and the only thing about an
offer a person owns.

- **It is an axis, not a verdict.** The filter says whether an advert is worth answering;
  the archive says whether it is on today's list. Two different questions, and the second
  one is why `FilterStage.STALE` no longer exists: age used to be reported as a rejection,
  which is what somebody reads when they ask why an offer is missing.
- **It cannot be a value in `offer.status`.** `FilterService.run()` reads the whole table
  with no `WHERE` and writes a verdict onto every row, because the rules are hot-reloadable
  and a partial re-judge would split the archive across two rule sets. An `ARCHIVED` status
  would be overwritten by `PASSED` on the next run — silently, and only for the offers that
  still pass.
- **Two columns, because there are four states.** `archived_at` with `AGE` or `MANUAL` is
  off the list; both null is on it; and `archived_at` null with `RESTORED` is on it
  *deliberately*, which is what stops the age pass taking it back off the next morning. A
  restore the next run undoes is a button that lies.
- **The age pass reconciles, it does not seal.** While the rule lived in the filter,
  staleness was recomputed every run, so widening `max_age_days` brought offers back. Rows
  the pass archived itself still come back; rows a person archived never do.
- **It runs between the filter and enrichment.** After the filter so a restored offer
  carries a current verdict, before enrichment because that is the stage that leaves the
  machine and scoring is the one that costs money. An offer off the list pays for neither.
- **An offer somebody is working on is never archived by age.** The exemption is
  `ApplicationStatus.isLive()` — not closed, and not `PACKAGED`, which is the state the
  packager opens with. Treating that one as "in progress" would exempt every offer that
  ever reached the shortlist, which is the whole shortlist.
- **A null `published_on` is never archived** and is counted in the report rather than
  passed over. It is the one way an offer can sit on the list forever.
- **`archived_at IS NULL` is the third part of the working-set predicate**, beside
  `status = 'PASSED'` and `duplicate_of_id IS NULL`, at all twelve sites. **The funnel needs
  it on both sides of the subtraction** or `survived` goes negative — the same defect
  duplicates once produced, and after a week the archive is the larger half of the table.
  `survived` equals the shortlist's own total, and that is the invariant to check when
  either number looks wrong.
- **The analytics deliberately do not get the predicate.** They are the record of what the
  market did, not the working list; excluding the archive would empty every chart older
  than the window. That their numbers differ from the shortlist's is correct.
- **The archive is a side, not a band.** A band is a range of scores; this decides which set
  the bands apply to, so it composes with them and with the search. `total` and the portal
  dropdown are counted over the side being read, or the filter offers a portal that
  produces an empty list and no reason.
- **A row archived from the list is dropped from it rather than replaced.** It is no longer
  part of the side being read, and leaving it there shows the working list carrying
  something that is not on it until somebody reloads.

## Manual status capture

`backend/…/application/`. The half of the loop the system cannot observe, and the first
write endpoint in the application.

- **The operator is the authority, so no transition is refused.** A project can be lost
  before it was ever answered, and a mistyped status has to be correctable without an
  argument. The eleven states describe the usual path; they are not a rule the tool
  enforces against the person who was actually there. A board that argues is a board
  nobody updates, and it is the only place this state exists.
- **What *is* checked is that the values make sense together.** Moving to a sent state
  with no date gets today rather than a rejection — the operator is recording something
  that already happened, and refusing would cost the status as well.
- **`clearFollowUp` is a `Boolean`, not a `boolean`.** Jackson refuses to map an absent
  value into a primitive, so every request omitting the flag came back 400 — which is
  every request, since the point of a PATCH is that it names one thing. The tri-state is
  also what the field means: leave it, set it, remove it.
- **A closing status drops the follow-up.** A lost project with a standing reminder is how
  a follow-up list stops being read.
- **Every change is an event row.** A single mutable row cannot answer "when did I send
  this" after the second correction. A date-only edit records no event; a status change
  does.
- **The application opens when the package is built**, because that is the first moment
  there is anything for a person to act on, and opening is idempotent so a second run
  never resets a status someone has already moved on.
- **`timestamptz` does not convert straight to an `Instant`.** The Postgres driver throws
  a `DataIntegrityViolationException` naming the whole query rather than the column. Read
  it with `getTimestamp(...).toInstant()`.

## The board and the write path

`frontend/…/core/store/applications.store.ts` plus `features/pipeline/` and the panel in
`features/offer-detail/`. The first screen in this app that writes.

- **The lanes come from `/api/applications/lanes`, not from a constant.** Eleven states
  across five lanes is a decision the enum already makes; a second copy in the browser
  disagrees with it the first time a state is added — visibly on the board, invisibly in
  the code.
- **`shared/` does not know what an application is**, so the picker takes plain
  `{ value, label }` options and emits a string. The layering rule is the reason, and the
  cast back to `ApplicationStatus` is safe for the same reason: the options came from the
  feature that owns the type.
- **Bind the selection on the option, not with `[value]` on the select.** `[value]`
  depends on the options existing when it is written; losing that race leaves the first
  option showing, so a card reads "New" while the badge beside it says SENT. No error,
  and picking the state it is already in looks like nothing happening.
- **The row is replaced with the server's answer, never with what was asked for.** The
  service dates a send itself and drops a follow-up on closing, so a locally patched row
  would disagree with the database until the next reload.
- **Clearing the follow-up is a button, not an empty field.** Emptying a native date input
  means deleting each segment in turn, and `clearFollowUp` is unreachable in practice
  without it — measured in the browser, not reasoned about.
- **The follow-up tile shows an em dash when the board did not load.** A zero and an
  unreachable API look identical on a tile, and that tile is the reason the dates get
  entered at all.
- **`saving` names the application in flight, not a boolean.** One card greys out; the
  rest stay usable.
- **A DOM-render screenshot is not proof of what the browser paints.** It serialises and
  re-renders, which drops DOM properties that have no attribute (a `<select>`'s
  selection) and some component CSS on SVG children (the score ring). Read the
  accessibility tree for state — `interceptor read` shows `combobox … value="SENT"` —
  and treat a capture as evidence about layout, not about widget state.

## The read side

`backend/…/offer/OfferQueryService` plus `config/SourceQueryService` and `RulesView`. Every
screen reads one of these, and none of them writes.

- **Read-only and separate from the stages that write.** Each pipeline stage owns a narrow
  slice of the `offer` row; this owns the whole row as a person reads it.
- **The working set is `status = 'PASSED' AND duplicate_of_id IS NULL AND archived_at IS
  NULL`.** All three parts, at every site that counts survivors — see § *The archive*.
- **The shortlist is primaries only, and so is everything that counts against it.** A row
  with `duplicate_of_id` set is the same project through a second portal, and it belongs
  inside the entry rather than beside it. The funnel and the sources screen's *survived*
  column count the same set — measured: counting every rejection against a primaries-only
  total made the rail report **-45 survivors**, and counting duplicates as survivors made
  the sources screen say 104 where the shortlist showed 96.
- **The detail is not restricted to survivors.** It is also how somebody opens an offer the
  filter rejected and asks whether the rule was right, so `/api/offers/{id}` serves any id
  and `hardPass` says which it is.
- **Reasons and duplicate clusters are two queries for the whole list, not two per entry.**
- **The shortlist is paged, and the filters go with the page.** The whole list used to come
  down and the browser filtered it; at 2,219 survivors that answer was 3 MB and it grows with
  every newsletter. The query string still holds the filters, so a filtered view survives a
  reload and is shareable as a link — only the deciding moved into SQL, where it now exists
  once instead of twice. A page of a browser-filtered list is not a page of anything.
- **Keyset, never `OFFSET`.** An offset re-reads and re-sorts everything before it on every
  page, and it skips or repeats a row whenever a run rewrites a score between two requests.
  The key is the whole sort tuple — `(coalesce(score_value, -1), ingested_at, id)` — because
  score alone is not unique: seven offers at 80 would let a page boundary fall inside a tie
  and the same row could arrive on both pages or on neither.
- **Every number printed beside the list is counted by the server, over the match.** The
  portal dropdown and the unscored count were both derived from the loaded entries, so they
  told a smaller truth the further you scrolled while sitting next to a sentence about the
  whole archive. The band boundaries moved for the same reason: they are the configured
  thresholds, and two literals in TypeScript deciding which offers a button shows is the
  second implementation this rule exists to prevent.
- **Loading more is a sentinel, not a button**, because the list is read by scrolling; its
  `IntersectionObserver` is attached in `afterNextRender`, since one attached before layout
  fires immediately against a zero-sized box and asks for page two before page one is drawn.
  **It cannot be verified in a backgrounded tab** — Chrome suspends the observer there, and
  the measurement comes back as a confident "nothing loaded". Measured through the
  Interceptor skill's `Tools/VerifyViewport.ts`: 50 offers, then 100 after scrolling.
- **`source_run` exists because nothing else can answer the announced-versus-extracted
  question.** The number of documents and the count a document announces about itself leave
  no trace in the `offer` table, and that comparison is the one check nothing else can make.
  One row per source per run, because the interesting question is when the number changed.
- **The sources screen lists the configuration, not the database.** A source that has never
  run still appears, because a misconfigured source being invisible is exactly the failure
  somebody is looking for when they open that screen.
- **Nor a threshold.** `lg-score` had 70 and 50 as input defaults and not one of its three
  callers ever overrode them, so every score ring banded off a constant while the rules
  screen and the analytics histogram showed the configured numbers. The shortlist's band
  filters had the same two literals and *decided which offers were shown* with them. Both
  now take `SCORE_THRESHOLDS`, a token provided from `core` and reached through
  `shared/shared.ports.ts` — the same seam the chart palette uses, for the same reason.
- **Nothing in the browser names a weight, a stage or a source type.** `scoring.weights` is
  an open map, the stages are the `FilterStage` enum, a source's `type` is whatever the YAML
  declares. A union type in TypeScript for any of them disagrees with the server the first
  time one is added — and the symptom is a compile error in a component that has no
  business knowing the filter at all.
- **The enum writes its stage descriptions as sentence fragments**, because that is how they
  read in a log line. The read side capitalises them; a chart label is not a log line.

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
- **`@Valid` goes on the type argument, never on the container.** `List<@Valid Skill>`
  validates the elements; `@Valid List<Skill>` is deprecated in Hibernate Validator 9 and
  logs a `HV000271` per component at every start. The configuration model is almost
  entirely lists of validated records, so getting it wrong once fills the startup log.

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
- **`.env` is read by the application, not by the build.** It used to be a `bootRun` hook, so
  launching the very same configuration from an IDE silently saw none of it: the value was in
  the file and the service said it was missing. The file is searched upwards from the working
  directory and real environment variables win, so every start path behaves identically.
  Compose reads the same file.
- **`.env` reaches Spring too, and it has to.** `DotEnvEnvironmentPostProcessor` registers it
  as a property source directly below `systemEnvironment`, so a real exported variable still
  wins and `application.yaml` now loses to the file. Without it the file meant two different
  things depending on which of the two readers a variable happened to be used by:
  `LEADGEN_CONFIG_DIR`, `POSTGRES_PASSWORD` and `SERVER_PORT` could be written there, be
  visibly present, and have no effect whatsoever — while Compose, which passes those same
  names as real environment variables, behaved exactly as written. It is a
  `SystemEnvironmentPropertySource`, so `SPRING_DATASOURCE_URL` maps the way an exported
  variable would, and it is registered in `META-INF/spring.factories` rather than as a bean
  because it has to run before the environment is bound.
- **`leadgen.packages-dir` and `leadgen.inbox-dir` are gone, and were read by nothing.** The
  packages directory is `packaging.output_dir` in `pipeline.yaml`, the inbox is a source's
  `path` in `sources.yaml`, and both are read by the tool itself. Their only effect was to make
  `PACKAGES_DIR` and `INBOX_DIR` look as though they meant something on the Spring side as
  well, which is how a value ends up written in the one place that is not read.

## The startup banner

`ConfigurationBanner`, beside `DatasourceBanner`. One box, one log entry, on
`ApplicationReadyEvent`.

- **Cumulative, not per file.** `application.yaml` and `.env` are two files with two readers,
  but nobody debugging a run thinks in files — they think "which database, which mailbox,
  which model". Both are merged into one view, grouped by subject, and every row says where
  its value came from instead of which list it was in.
- **Effective, not declared.** A `${POSTGRES_PORT:55432}` shows the port in use, and a `.env`
  key a real environment variable overrides shows the value that wins. Otherwise the banner
  disagrees with the resolver exactly where it matters.
- **App-relevant is measured, not listed.** A `.env` key is shown when a `${...}` in
  `application.yaml` or in one of the four `leadgen/*.yaml` files names it — read from the raw
  text, in both layers. `WEB_PORT` and `API_PROXY_TARGET` belong to the dev server and to
  Compose, and showing them invites the reader to change one and wait for an effect that
  cannot come. The count of what was left out is printed, so "left out" never means "lost".
- **A variable both files name is one row, not two.** It appears under the property that
  consumes it, carrying the value that won.
- **Every row names the layer that decided it**, `env` before `.env` before `yaml`, which is
  the precedence `DotEnvEnvironmentPostProcessor` registers.
- **`Secrets` decides by key name, because a password is not recognisable by looking at it.**
  The only safe direction to be wrong in is masking something harmless. The mask is a fixed
  width — stars matching the length would publish the length — and masked, empty and unset are
  three different renderings: whether a secret is configured at all is the one thing about it
  worth logging. Credentials inside a value are masked too: `scheme://user:password@host`.
- **The icons are emoji from the block with no text-presentation past, and no `U+FE0F`
  anywhere.** A legacy symbol like `⚠` is one column in some terminals and two in others, and
  either way the border is torn off exactly the rows that carry an icon. Padding is computed in
  display columns, not in `String.length`.
- **A banner must not be able to end a startup.** An unresolvable placeholder is printed as
  such, an unreadable file contributes nothing, and neither throws at the last moment before
  the process is ready.
- **A `@DynamicPropertySource` supplier is called once per resolution, not once per context.**
  Reading `leadgen.config-dir` for the banner made `PackagingServiceTest` build a second temp
  configuration and reassign the static it asserts against — it then deleted a CV the
  application was never going to open. Anything with a side effect in such a supplier has to
  be memoized.

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
frontend/src/app/core/            api seams, stores, models, theme, shell
frontend/src/app/layout/          shell, header, nav rail, theme toggle
frontend/src/app/shared/          icon, brand mark, score, funnel rail, badge, stat tile,
                                  empty state, page header
frontend/src/app/features/        dashboard, shortlist (+ offer card), offer detail,
                                  pipeline, review, sources, rules
frontend/tools/build-favicon.sh   renders favicon.ico, favicon-256.png and logo-mark.png
```

The two Python scripts are the **reference implementation**. Whatever they do, the Java
code has to reproduce — the numbers in `docs/SAMPLE-ANALYSIS.md` are the target values.

## Measured baseline

- 14 mails, **1289 offers**, all extracted deterministically via CSS. The count announced
  in the subject matches exactly in all 14. `fallback: none` for this source.
- **0.0 % contain an hourly rate.** Rate, duration, workload and start date only arrive
  from the enrichment stage (fetching the original ad from the portal).
- The hard filter's share depends entirely on the rules, so the archive's own measurement
  is written by `simulate_filter.py` into `docs/samples/filter-baseline.json` and the corpus
  test asserts against that file rather than against a number kept here. At
  `min_remote_percent: 40` it is **19.1 %** — 246 of 1289, ~18 per mail after
  deduplication. That is the daily LLM budget, and the archive window narrows it again on
  top. The stages and the three defects that moved this number are in
  `docs/SAMPLE-ANALYSIS.md` § 5. The share is not comparable across settings: at
  `min_remote_percent: 0` the same corpus gave 41.5 %, because the reach rule switches off
  entirely at zero.
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
4. ✅ **IMAP connector** — same extraction, different source. Progress **never** via
   seen/unseen: the owner reads the same mails on a phone. It began as a `UIDVALIDITY`/`UID`
   watermark and is now Spring Integration's user flag; the three guarantees that cost is
   named under § *Ingest and extraction*.
5. ✅ **Dedupe** — `DeduplicationService` clusters after every ingest run, globally rather
   than per source, because the whole point is one project reaching the pipeline through
   several portals. One SQL statement, idempotent by construction.
6. ✅ **Hard filter** — six stages, every list from configuration or the profile, no
   model and no network. Reproduces `docs/samples/simulate_filter.py` exactly, and the
   corpus test asserts it against the baseline that script writes rather than against
   numbers anybody keeps in step by hand.
7. ✅ **Enrichment** — HTTP fetch of the original ad, rate limit, cache, `robots.txt`.
   A failed fetch is not a knockout; the offer stays in as *incomplete*.
8. ✅ **Scoring + digest** — deterministic factors plus a model for the four that need
   judgement, and a digest written to a file at the end of every run.
9. ✅ **Packaging** — cover letter from a Freemarker template plus the reference projects
   the offer's own skills selected, the fixed PDF for the ad's language, the archived
   original and a `meta.json`. A folder on disk; nothing is sent.
10. ✅ **Frontend** — design system, shell and all six screens, every one of them on a
    real endpoint. `GET /api/offers` and `/api/offers/{id}` carry the shortlist and the
    detail, `/api/offers/funnel` the filter counts, `/api/sources` and `/api/rules` the
    configuration as the screens read it. `core/fixtures/` is gone.
11. ✅ **Manual status capture** — the `application` table and its event log,
    `GET/PATCH /api/applications`, and both screens on it: the board groups by the lanes
    the endpoint states, and the offer detail carries the same control plus the dates,
    the note and the history. The dashboard's follow-up tile counts what the server
    called due. The tool never sends — it finds, filters, scores and packages; the
    operator sends the mail and therefore records the outcome by hand.
12. ✅ **Manual entry** — an offer found by hand must be able to enter the pipeline, or the
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
- **A test that proves the keyless path must not read the developer's `.env`.** Placeholder
  resolution reads the process environment and then `.env`, whichever test is running, so
  `ScoringWithoutAModelTest` started scoring against a real endpoint the moment a key was
  filled in — and the test that exists to prove the tool works *without* a model failed for
  the one person who had finished configuring it. It empties the `${LLM_*}` placeholders in
  the materialised copy: what is under test is the code path, not whose machine it runs on.
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
- **The DOM-render screenshot is evidence about layout and colour, not about state or
  reflow.** It serialises and re-renders, which drops DOM properties that have no attribute
  (a `<select>`'s selection), some component CSS on SVG children (`fill` on the score ring),
  and it mis-measures text that wraps inside a flex item — three separate false alarms in
  one session. Read the accessibility tree for widget state (`interceptor read` prints
  `combobox … value="SENT"`), and confirm a suspected overlap in a real browser before
  changing CSS. The Angular dev server sets a CSP that blocks `interceptor eval`, so the
  geometry cannot be measured through it either.
- **ImageMagick renders SVG with its own parser and drops paths containing arcs** unless
  `rsvg-convert` is on PATH as its delegate. The first favicon looked broken for that
  reason alone, with the geometry perfectly correct.

## Open

- **CI.** The tooling baseline is in place (`.editorconfig`, ESLint, Prettier,
  Stylelint), but no pipeline runs it yet.
- Which folder in the IMAP mailbox the newsletter lands in — deployment detail, and it
  does not belong in a committed file.

## Settled

- **License: Apache-2.0.** `LICENSE` and `NOTICE` at the root, SPDX headers on the Java
  sources enforced by Spotless rather than written by hand.
- **The repository is `codeministry/leadgen`**, which is why the Java package
  `de.codeministry.leadgen` stays as it is.
- **No Helm chart in the repository.** Docker Compose is the supported way to run this;
  a chart is a later phase and the README no longer claims one.
