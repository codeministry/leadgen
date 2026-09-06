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
- **The mark is a mask, and nothing in the header re-scopes `data-theme` any more.** The asset is a flat single-colour
  silhouette — `tools/build-favicon.sh` already keys out the white and repaints from the alpha channel alone — so the
  only thing in it worth keeping is the shape.
  `.mark` fills it with `--color-primary`: petrol at 6.15:1 on white and 5.80:1 on the sand page, well past the 3:1 a
  graphical object needs, and on the dark theme that token *is* the logo's own cyan, so nothing visibly changes there.
  It also puts the mark and the wordmark's "LEAD" on one token; they used to be two different blues 20px apart. The dark
  plate it replaces was scoped with `data-theme="lg-dark"` on the brand link, and before that on the whole `<header>` —
  the navigation moving in ended the second one, because below 48rem that navigation is a fixed bottom bar and inside a
  dark-scoped header it would be a dark bar under a light page. What ended the first is a measurement: `#161F22` on the
  white bar is **16.8:1**, against 15.8:1 for body ink on the sand page. The plate was the highest-contrast object in
  the entire light theme and the only one carrying no information, which is why it read as pasted on rather than as a
  badge. The
  `--lg-*` corrective tokens still re-scope with `data-theme` wherever it is nested; the brand zone simply no longer
  needs it. A mask has no intrinsic ratio, so `BrandMark` writes both axes from the asset's own 116×128 box, and
  `forced-colors` gets an explicit fallback because a masked element there disappears outright.
- **The navigation is a row in the header, and it is the same seven links at every width.** It used to be a left rail
  that collapsed to icons; the row keeps that collapsed presentation between 48rem and 90rem and drops the wordmark with
  it, and below 48rem it becomes the fixed bottom bar the rail already turned into. Nothing hides behind a disclosure:
  seven destinations are the application's map, and a map behind a click is a map nobody reads. What carried over is the
  vocabulary of "you are here" — the primary colour, a 16 % wash and a 2px marker, rotated from the left edge onto the
  bottom one. Ochre stays out of it; it means "this survived the filter" and nothing else.
- **The bottom bar sizes its shares on the `<li>`, not on the link.** `ul` lays out the list items, so `flex: 1 1 0` on
  the anchor inside one sizes nothing, and seven content-sized items run off a 375px screen. Measured: the seventh was
  cut off by the screen edge while `scrollWidth` reported no overflow at all, because the bar is `position: fixed` and
  clips instead of growing the page. At 320px two labels ellipsise, which is the accepted floor.
- **The header is sticky, and that is what lets a screen hand its scroll to the document.** A header that scrolls away
  takes the run button and the theme toggle with it, and on a long advert they are gone for the whole read.
  `z-index: 30` is above the 20 the bottom bar takes; `position:
  sticky` does not create a containing block, so the fixed bar still resolves against the viewport even though it now
  lives inside the header. Sticky is inert on a `.shell.fill` screen, where nothing scrolls at all.
- **The navigation is content-sized above 48rem, and that is what the empty bar was.** It used to claim `flex: 1` on the
  host, on `.topnav` and on `ul` while the `<li>` stayed content-sized, so the row swallowed every spare pixel and
  packed the links against the brand — a thousand pixels of nothing between the last link and the run button on a wide
  monitor, which is what "unevenly distributed" was pointing at. `.ops` still pushes itself right with an auto margin,
  so the space lands between the two groups instead of inside one. Below 48rem the growth is load-bearing and stays: the
  fixed bar is the whole width and `flex: 1 1 0` on the `<li>` needs a growing `ul`.
- **One gutter and one chrome gap, both tokens.** `--lg-gutter` is the inline padding of the content *and* of the header
  row; they used to be 1.5rem and 0.75rem, so at every width the brand mark began 12px left of the first thing on the
  page — permanent, small, and impossible to point at. `--lg-content-gap` is the vertical distance between the two:
  1.5rem against a 3.5rem bordered bar is a ratio of 0.43 and reads as content pressed against chrome, so it is 2.5rem,
  dropping back to 1.5rem below 48rem. **`--lg-sticky-top` is derived from it** rather than written, which removes a
  jump that was already shipping: the offset was `header-h + 0.75rem` against 1.5rem of padding, so a pinned list column
  moved 0.75rem the moment it docked, and at 2.5rem that jump would have been 1.25rem and looked like a bug. The
  `100svh` subtraction in both split stylesheets reads `--lg-gutter` for the same reason. The `< 48rem` override lives
  in
  `tokens.css` and not in the shell's stylesheet, because Angular's emulated encapsulation appends its attribute to
  every selector it is given — `:root` included, which then matches nothing. The gutter narrows to 1rem below 48rem and
  the header follows it: a phone is where the margin is worth the most and where a 7px disagreement between chrome and
  page is most visible.
- **The brand link carries `margin-inline-end: 0.75rem` on top of the row's gap.** The lockup is an identity and the
  seven links are a map; at the row gap alone they read as one group and the first destination looks like part of the
  wordmark. It sits on the brand and not on the nav, so nothing moves below 48rem where the nav leaves the row entirely.
- **The settings popover recomputes the row's right edge.** A popover is in the top layer, whose containing block is the
  viewport whatever `position` says, so `absolute` inside the capped row would not follow it either. `inset-inline-end`
  is
  `max(var(--lg-gutter), calc((100vw - var(--lg-shell-max)) / 2))`. **`inset: auto` in front of it is load-bearing:**
  the UA gives `[popover]` `inset: 0` with `margin: auto`, so an unset `left`
  stays 0 and the panel pins itself to the *window's* left edge at every width — measured exactly that way once, left 0
  and right 190 at 2560, while `:popover-open` and `aria-expanded` both reported the truth. `100vw` includes the
  scrollbar, so this one is checked in a real browser rather than reasoned about.
- **Theme, language and the version live in a popover, and the version is split in two.** They are three things read
  once against two that are operated on every screen, and the two radiogroups cost 220px of a bar the navigation now
  needs. A native `popover` rather than `role="menu"`, which would impose menuitem semantics and a roving tabindex both
  toggles violate, and rather than a modal dialog, which would trap focus for "switch to dark"; `aria-expanded` is
  mirrored from the panel's own `toggle` event, because `popovertarget`'s implicit state is not evenly supported. The
  panel's content is rendered whether it is open or not — a closed popover still contributes its text, and an `@if`
  around the version would take it out of the DOM and out of `app.spec`, far from anything that names it. The version's
  *digits* moved in; `connecting` and the error stay in the bar, because a backend that stopped answering must not need
  a click to be noticed.
- **`system` is the absence of `data-theme`.** DaisyUI emits `lg-dark` under
  `:root:not([data-theme])` inside a `prefers-color-scheme` query, so removing the
  attribute *is* "follow the operating system". An inline script in `src/index.html`
  applies the stored preference before first paint and shares the `lg-theme` key with
  `core/theme/theme.model.ts`.
- **The default is `light`, and it is written rather than implied.** `system` is still one of the three choices; it is
  no longer what an unconfigured browser gets, because the palette is designed light-first and a reader on a dark-set
  machine used to meet the dark variant before ever seeing the light one. Since the absence of the attribute *is*
  "follow the OS", the default cannot be expressed by leaving it off: the inline script writes
  `data-theme="lg-light"` when nothing is stored, and `DEFAULT_PREFERENCE` in
  `core/theme/theme.store.ts` is the other half of the same decision. Two places, kept in step by hand, exactly like the
  `lg-theme` key itself.
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
- **`min_hourly_eur` before enrichment is rejected at load time** — the invariant is enforced, not just written down. So
  is `review` above `auto_shortlist`: `Score.band`
  tests the shortlist bound first, so the inverted pair does not fail, it silently deletes the REVIEW band and builds an
  application package for every offer above the lower of the two. Found live, with `auto_shortlist: 30` sitting under
  `review: 50`.
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
- **The search term is this connector's own, and it must be.** Spring Integration's default
  `SearchTermStrategy` excludes every message carrying `\Seen` as well as the ones carrying
  the user flag. Pointed at a mailbox its owner reads on a phone, the receiver therefore
  hands over nothing, the run reports zero documents, and nothing errors — measured against
  the real mailbox: 165 mails in the folder, 165 matching `NOT KEYWORD leadgen`, 0 matching
  the default term. Not marking `\Seen` is pointless if progress is read off it. The
  replacement asks one question, "not deleted and not carrying the `leadgen` flag", and
  `\Deleted` is in there because an unexpunged deletion is not a document, not because it
  is progress. Every test before this delivered a fresh, unseen mail, which is why the suite
  was green for a connector that returned nothing.
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
- **A long advert is folded, and the decision is a character count rather than a measured height.** A portal ad runs to
  several thousand characters with everything the tool decided underneath it, so unfolded the ad *is* the page.
  Measuring the overflow would mean
  `scrollHeight` against a clamp or a `ResizeObserver`, and both are suspended in a backgrounded tab — the toggle would
  be missing exactly where a screenshot says the page is fine. The clamp is a `max-height` plus an alpha-ramp mask, not
  a truncated string, so the Markdown stays whole in the DOM and the browser's own find still reaches the end of it. No
  transition either: a height animation between a clamp and `auto` needs a measured target.
- **What is unfolded is an offer id, not a boolean.** A boolean survives the navigation to the next offer, so its ad
  opens too, for a decision nobody made about it. Holding the id makes the reset fall out of the comparison and needs no
  effect to undo it.

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
- **A refusal from the rate limiter is deferred, never recorded.** The limiter refuses
  rather than waits, so a backlog larger than the limit is the normal case on a first full
  pass. Recorded like a failed fetch it stamps `enriched_at`, and the due query is
  `enriched_at IS NULL` — the offer is then never fetched again and is scored on the
  newsletter summary alone. Measured: 480 due, 20 fetched, 460 written off, 0 left due.
  `FetchResult.deferred` writes nothing at all, and `EnrichmentReport` counts it apart from
  `incomplete` because the difference between the two is whether the offer comes back.
- **`max_per_run` is how long a pass waits, and the window is untouched by it.** Refusing rather than waiting is right
  for the limiter and wrong for the pass on top of it: a run did one minute's worth and deferred everything else, so a
  backlog needed one run per
  `rate_limit_per_minute` offers to clear. It never did — measured on the live database, **2,537 offers carried
  `enrichment_note = 'rate limit reached'` with a stamped
  `enriched_at`** and only 23 rows in the whole table had a `full_text`. `AdFetcher` now waits for a permit the window
  would have granted anyway, up to the run's budget, and refuses beyond it. Unset means the old behaviour, so a
  configuration written before this key behaves as it did.
- **The stage is therefore deliberately not `@Transactional`**, the same shape
  `ScoringService` documents. Each result is one statement and nothing needs atomicity across offers; held as one
  transaction, a pass that now waits for minutes by design would hold a write lock on every offer it had touched, and
  any concurrent filter stage — which writes a verdict on every row — would sit behind it.
- **The `pause` seam is why the waiting is testable.** The wait is computed from the injectable clock and served by the
  real one, so a frozen clock would mean a minute of actual sleeping and then a window that never frees. Overridden, a
  test moves the clock by the same amount instead.
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

- **Rules before model, again.** `RuleScorer` decides everything the profile and the offer's own fields can decide —
  skill overlap with aliases, rate against the floor,
  seniority, how much of the engagement's shape is stated, industry — for free. A `Judge`
  is asked only about role fit and the three penalties.
- **The total is a share of what was attainable, not a sum.** A factor the offer said nothing about writes no reason at
  all and is in neither half of the fraction; a factor that had something to say and scored badly writes a 0-point row
  and stays in the denominator. `ScoreReason.maxPoints` is what carries the distinction, and it is on the row so the
  screen can read "23 / 45". Summed instead, the scale was capped by things no offer could influence: the sources state
  a rate in 0.0 % of offers, so **all 101 scored offers carried a 0-point `rate_fit`**, `project_setup` averaged 0.2 of
  10, `industry_fit`
  fired **zero** times, and the highest score in the whole table was **53**. The accepted consequence is that the less
  an ad states, the more its skill overlap carries — an offer is judged on what it says.
- **`project_setup` is a bonus and the penalties are deductions; neither is in the pool.**
  Inside the denominator, an ad naming one of duration/workload/start scores 3 of 10 and lands below one that names
  nothing at all, because naming nothing keeps the factor out of the denominator entirely. As an absolute addition it is
  monotone. It is also the only factor that measures the advert rather than the fit.
- **Skill overlap is weighted and saturates; it is not a count.** `matched.size() /
  core.size()` ignored the per-skill weights and read neither `strong:` nor `peripheral:`
  despite the weight table's own comment saying otherwise — so an ad asking for Kafka, PostgreSQL, Keycloak and CI/CD
  scored nothing for four things the profile is strong in, and a backend ad was charged for not naming Angular. The
  matched weights are added up (peripheral at half) and measured against the `scoring.saturation_core_count` heaviest
  core skills, because no advert names a whole profile and requiring one requires something that never happens. Measured
  over the corpus: the factor never once exceeded five of eight core skills.
- **A composite skill name is split on `/` before matching.** Folding keeps a name whole, so `REST / API-Design` is the
  phrase `rest api design` and matches only an ad that writes it in that order. No ad does; both halves are offered to
  the matcher instead.
- **An industry is matched through `match:`, not through its name.** The profile names an industry in this repository's
  language and the adverts are German, so `Insurance` was compared against text that says *Versicherung*. Same shape and
  same reason as a skill's aliases; the name is still tried, so a profile written before this behaves as it did.
- **An alias has to be specific enough to mean something.** `Build` for Gradle and
  `Reporting` for Superset matched a plain Java backend ad and added nine points of skill weight for words that say
  nothing about a stack. A profile is data, but a generic alias is a measurement error in it.
- **Unscored is not zero, and not nothing.** With no key the deterministic reasons are
  still written, so the operator sees "+45 core skill overlap, +10 rate fit". What is
  withheld is the *total*: computed from five of the nine weights it would not be
  comparable to one from all nine, and the same offer would score differently depending on
  whether a key happened to be configured that morning.
- **A judge that answered nothing is the keyless case, not a low score.** `role_fit` is the one factor the prompt
  requires even at zero, so its absence is an unreachable endpoint, a reply that was not JSON, or a model that ignored
  the instruction — never an opinion.
  `Judge.answered` is the single reader of that, on all three paths (run, batch collector, and the rescore button, which
  says so out loud rather than showing a fresh number). Measured before it existed: **63 of 101 scored offers had no
  judged factor at all** and every one of them still carried a total. It is self-healing, because a null `score_model`
  makes the offer due again, and `ScoringReport.unusable` puts it in the run's own log.
- **A judged zero is kept; a zero penalty is dropped.** A weight is a share of what was attainable, so "this role does
  not fit" has to stay in the denominator or a bad match reads as a good one. A penalty is an absolute deduction, so a
  zero one is nothing at all.
- **The prompt carries the profile, and it is read rather than restated.** It used to say
  "a senior Java, Spring Boot and Angular developer who works from Germany" — three skills of the twenty-nine in
  `skill-profile.yaml`, hard-coded, while every other stage read the file. Role fit was judged against a description of
  somebody else, and editing the profile could not move it. `describe(offer)` likewise passes the rate, duration,
  workload and start: those come from enrichment and not from the advert's prose, and a judge calling an offer vague
  while the row beside it states all four knows less than the application does.
- **A stub that is not a whole chat completion proves nothing.** `JudgeIsBuiltPerRunTest`
  sent `choices` alone; the SDK refused it with "`id` is not set", the judge caught that and returned no reasons, and
  the test stayed green because a run counted an offer as judged whether or not an answer came back. What it actually
  proved was that a judge gets *built*
  after a reload.
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
- **One offer, one transaction — and the stage is deliberately not one.** `ScoringService.run`
  carries no `@Transactional`; `ScoreWriter.write` does, because the three statements behind a single score have to
  commit together. Wrapping the loop instead holds a write lock on every offer already judged until the last is
  answered, which with a local model is hours, and the filter stage of any concurrent run writes a verdict on all rows
  with no `WHERE` — so it waits behind it. Measured: two filter updates blocked thirteen minutes behind a scoring
  transaction open for twenty. `ScoringTransactionBoundaryTest` pins both annotations, because a passing run reveals
  nothing about which one is missing.
- **A pass refuses to start while one is running.** `IngestService` holds a `tryLock` and answers `409`, never a queue:
  a second pass is the same work done twice, and a caller that waits is a request held open for hours. The CronJob's
  `concurrencyPolicy: Forbid` governs only the jobs the CronJob creates and says nothing about the button.
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
- **The board shows the working list, not every application ever opened.** `ApplicationService.BOARD`
  carried no `WHERE` at all, so an archived offer kept its card and kept counting towards the dashboard's follow-up
  tile. Age never puts a live application there — `ApplicationStatus.isLive()`
  exempts it — so what the predicate hides is something a person archived by hand, which is the clearest statement
  available that they are done with it. **`find(id)` deliberately does not get the predicate:** it is what `update`
  reads before and after a write and the offer detail is reachable for any offer, so filtered there too, archiving an
  offer would make its own status uncorrectable. It also stopped scanning the whole board to find one row. The accepted
  consequence is that an archived offer's detail shows no status panel, because the browser looks the application up in
  the board list it already holds.
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

## The split views

`features/shortlist/`, `features/pipeline/` and `features/review/`, with `layout/app-shell/`
underneath all three. One list on the left, one thing being read on the right, and neither column scrolls the other.

- **The board is bounded; the shortlist and the review hand their reading column to the document.** A route asks to be
  bounded with
  `data: { fill: true }` and `AppShell` reads that exactly where it reads `data.measure`, because the element that has
  to stop scrolling is an ancestor of the screen. `.shell.fill` takes a real `height: 100dvh`: `min-height` alone is not
  a height, the flex chain resolves against it only while the content is shorter, and a long list simply grows the shell
  past the viewport, so the page scrolls and the panes never do. Measured that way before the `height` was added. The
  board keeps it, because five lanes plus a reading column dividing a *growing* page is two gestures with two owners and
  `.board-col` is deliberately one scroller for both axes. The other two gave it up: the advert and the uploaded
  document are the prose surfaces here, a document scroll is what a reader's hands already know, and an inner scroller
  on a phone buys nothing at all. On both the list column is `position: sticky` with a scroller of its own while the
  reading column simply grows. On the review that also means `.page-head` — the title and the drop zone — scrolls away,
  which is right: uploading is a one-off and correcting is per document. `align-items: start` on the grid is
  load-bearing and looks cosmetic — a grid item stretched to the row height has no room to move in and
  `position: sticky` on it silently does nothing. `100svh` and not `100dvh` for its max-height: the dynamic unit changes
  as a mobile browser shows and hides its chrome, which would reflow the column on every gesture.
- **The sentinel's root is conditional, and that is the regression this model can cause.** Above the breakpoint the root
  is the list pane, which is a scroller. Below it the pane's `overflow` is `visible` and the root is `null`, the
  window — an `overflow` box that clips nothing is still a valid `IntersectionObserver` root, and against an unclipped
  root the sentinel intersects on the first frame and pages the whole archive without anybody scrolling. Measured on the
  archive, 2,197 rows: at 1440 the document scrolled to its end leaves 50 entries and the list pane's own scroll brings
  the next 50; at 1151 the document's scroll brings them. `bothColumns()` is the same `matchMedia` signal the
  auto-selection reads, so the breakpoint is still stated once per side.
- **The three claims that had no test now have one each, and one of them is only half testable.**
  `pipeline.spec.ts` drives the real router: it opens `/pipeline`, navigates to `/pipeline/7` and asserts the board is
  *not* fetched again, which is the child-route reuse the pattern exists for. The picker's is split by what jsdom can
  answer — the CSS half (`position: relative; z-index: 1` above the stretched link) is asserted as the structure it
  depends on, that the picker is not a descendant of the anchor, and the behavioural half by picking a status and
  asserting a PATCH with the URL unchanged. `review.spec.ts` covers confirm and reject: both end the document, so both
  have to drop the name from the query string, and the navigation settles a microtask after the call, which is why the
  spec awaits `whenStable()` before it reads `router.url`. The shortlist's conditional sentinel root has no spec of its
  own on purpose: jsdom has no `IntersectionObserver`, `LoadMore` guards on exactly that, and `load-more.spec.ts`
  already covers the conditional-root shape.
- **The detail's reset moved from the pane to the document.** The right column has no scroller left, so what has to go
  back to the top when the next offer opens is `scrollingElement`. The list column is pinned, so the reader keeps it
  while that happens — which is also why pressing `j` deep inside a long advert is not the yank it would otherwise be:
  the list never left the screen, and the page returns to the top of the newly selected ad on purpose.
- **The board's reading column exists only while something is being read, and its lanes divide the pane at every
  width.** Reserved, the column was 30rem the board did not have with nothing in it — at 1440px the board sat in two
  thirds of the screen, scrolling its five lanes sideways, next to an empty panel. Measured after: 1178px of board and
  225px lanes with nothing open, 709px and the sideways scroll once an offer is. The lanes were also pinned to 15rem
  below the split's breakpoint, which made the board scroll at widths where five stretched lanes still fit; the 14rem
  minimum in `minmax()` is what makes the pane scroll when they genuinely do not.
- **One cap, `--lg-shell-max`, and the header row takes it too.** The measure used to be left-aligned against the nav
  rail, which was the shared left edge every screen started at; with the rail gone there is no such edge, and
  left-aligned the surplus on a wide monitor is simply ragged on the right of every screen. Centring it alone was not
  enough, because the header stayed full-bleed: at 2560px its ink totalled about 1240px and the remaining ~1300px was
  empty bar, next to content sitting in a centred column. Chrome and content were two layouts on one page. Now
  `.header-row` caps and centres against the same bound, **plus both gutters** — the page's gutter lives on `.content`
  and sits *outside* the capped `.measure`, while the row's lives inside it, so at the bare 104rem the two boxes
  coincided and the ink did not: the brand mark began 22px right of the page's first character. Measured live and then
  equal to the pixel at 2560, 1920, 1600, 1440, 1152, 768, 767 and 390, on both edges. The bar itself stays full-bleed —
  the page scrolls *under* it, so a bar that stops short of the window lets content past it at the ends, and below 48rem
  the navigation is a full-bleed fixed bottom bar an inset top bar would contradict. **`--lg-measure-wide` (120rem) was
  deleted because it was unreachable:** both split screens cap their own host at `--lg-split-max`, so above 1560px the
  child cap won and below it the window did, and `.measure.wide` was observationally identical to no cap at all. Same
  class as `remote.accept_unknown` — read, applied, and changing nothing — and it is exactly the number someone would
  have capped the header at. `--lg-measure` and `--lg-split-max` are now aliases of the one bound; `data.measure` has
  two states left, `full` and absent.
- **The list column takes 34rem and the advert gives them up.** The card is what is scanned twenty at a time and it
  carries a title, four meta values and a score; the advert is prose and was the wider of the two by a long way. It
  stays a fixed width — a proportional split re-wraps the card's meta row on every monitor. One token for the shortlist
  and the review both: the review's queue is the same card read the same way. The cost is at the bottom of the
  two-column range, where the reading column is 4rem narrower than it was; at 1024px with the nav rail open it is around
  15rem, which was already too narrow before this change.
- **The board takes the whole window; every other screen takes the shell's bound.** `data.measure` is a string and not a
  boolean even at two states — a `full` flag beside the `wide` flag it used to have is two booleans on one axis, and
  then the stylesheet's order silently decides; the name is also what let the dead third state be found and removed. The
  board has five lanes and a reading column dividing whatever width there is, so every rem a cap withholds is width
  taken off all five, and `.measure.full` is `max-width: none` rather than a bigger number: a cap wide enough for
  today's monitor is wrong on the next one. That is also why there is no `full` token. The header row stays capped above
  it, and that is deliberate: the board's outer edge is a scroll-container edge, not a text edge, so chrome over a
  workspace is a different relationship than chrome over a reading column.
- **Every `min-height: 0` down that chain is load-bearing.** A flex item's default is its content height, which is
  exactly how a "bounded" pane grows the page instead of scrolling, and it looks correct in a screenshot while doing it.
  The chain is
  `.shell.fill` → `.body` → `.content.fill` → `.measure.fill` → the feature's `:host` →
  `.split` → `.pane`.
- **`<router-outlet>` gets `display: none` inside `.measure.fill`.** It is a comment anchor with no box; in a flex
  container it would still take a slot. The routed component is its next sibling and carries the height.
- **The breakpoint is 72rem on all three, and below it the list is hidden rather than overlaid.** It was 80rem, measured
  with the nav rail **open** — the worse of two states no media query could see, because the rail went from 4rem to
  14.5rem on a click with no breakpoint of its own. That rail is gone, so there is one state left and the old number
  defends a layout that no longer exists. The floor it defended is the reading column it produced in the bad state,
  489px. Without the rail that column is `min(V - 45, 1560) - 528.75`, so 72rem yields 578px and 64rem would yield
  450px. Measured after the change, on all three screens: two columns at 1152 and one at 1151, the shortlist and the
  review at 578px of reading column and the board at 638px of lanes beside its fixed 30rem panel; 706px at 1280, where
  the detail's panels now sit two-up. 48rem is where the navigation becomes a bottom bar and stays its own number:
  stacking two structural relayouts on one makes both harder to check.
- **A `rem` in a media query is not a `rem` in a rule, in this repository.** `html` sits at `font-size: 93.75%`, so the
  layout's rem is 15px while a media query resolves against the initial 16px whatever the root says. `72rem` is
  therefore 1152px, and `--lg-list-w: 34rem` is 510px. Comparing the two numbers as if they were the same unit is how a
  breakpoint gets picked for a column width it does not actually produce.
- **The selection is a route, never local state.** The URL is what a deep link, the back button and a click all agree
  on, and a second copy in a signal disagrees with it the first time one of the three is used. Read from
  `route.snapshot.firstChild` with `NavigationEnd`
  as the reason to look again — the shape `AppShell` already uses.
- **Two of the three use a child route; the review uses a query parameter, and that is not a style.** The shortlist and
  the board have a real component on the right (`OfferDetail`), so a child route has something to render. The review's
  document is already in the store the screen reads, so a child route would render a component whose only job is to look
  up by name what the parent is holding — and Angular refuses a componentless leaf route outright with `NG04014`, which
  is the framework making the same point.
- **A child route and not a second flat route on the same component.** Two `Route` objects are two configurations, so
  the default reuse strategy destroys the list on the first click:
  it refetches, `entries` falls back to page one, and the scroll position is gone. A single route cannot express an
  optional path parameter.
- **`queryParamsHandling: 'preserve'` on every card link.** Without it the first click drops the query string, the list
  reloads unfiltered, and it reads as a store bug rather than as a missing attribute.
- **The whole card is the link, through one stretched anchor.** A click handler on the article would need its own
  keyboard path to satisfy `click-events-have-key-events` and
  `interactive-supports-focus`, and it would be a second way to the same route. Two costs come with it: text in the card
  can no longer be selected with the mouse, and **any control on the card needs `position: relative; z-index: 1`** or it
  stops being clickable — which is what the board's status picker carries.
- **Selection is petrol, never the accent.** Ochre means one thing in this application: this survived the filter. The
  selected row takes the primary border, `--lg-selected-surface` and a 3px edge marker, the same vocabulary the nav rail
  uses for "you are here". Hover tints the border only, so hovering a selected card never reads as deselecting it.
- **`ShortlistStore` keeps two loading/error pairs.** `listLoading`/`listError` and
  `detailLoading`/`detailError`. They used to be one pair, the shortlist template branches on the error first, and a
  single failed detail fetch therefore blanked the whole list beside it. The same reason `rescoreError` was already kept
  apart.
- **`LoadMore` takes a `root`.** Its `IntersectionObserver` measured against the window; in a pane that scrolls on its
  own the sentinel then intersects on the first frame and on every frame after, and pages the entire archive without
  anybody scrolling. `rootMargin` is the other half: with the window as root and a scroller in between, the margin
  expands the window's rectangle while the pane still clips unmargined. The root is a template reference on the ancestor
  `<section>`, not a `viewChild`, so it is a real element on the first pass.
- **The fill layout applies at every width, which is what makes that root safe.** One scroll model everywhere, no
  `matchMedia` in TypeScript duplicating a CSS breakpoint, and nothing that changes behaviour when a window is dragged
  across the split's own breakpoint.
- **The detail's panel grid is a container query, and the container is the pane.**
  `@container detail (width < 44rem)`. The viewport cannot answer for that column: the nav rail expands from 4rem to
  14.5rem **with no media query at all**, so at 1280px the detail is 47.5rem collapsed and 37rem open and no viewport
  number is right for both. The
  `container-type` sits on `.detail-pane` and deliberately not on `lg-offer-detail`:
  containment must not land on an element whose height has to grow, and the pane's height comes from the flex chain
  rather than from its content.
- **Keyboard navigation is bound to the list pane, not to the document.** `keydown` bubbles from the focused card link,
  so the handler fires only while the focus is in the list — which is what lets `j` stay a letter in the search field
  and leaves the arrow keys scrolling the advert while the reader is in the detail column, with no target sniffing
  anywhere. The pane carries `tabindex="0"` because a scrollable region has to be reachable by keyboard at all. Past the
  last loaded entry the key asks for the next page and stays put: the list is keyset-paged, so "next" beyond what is
  loaded does not exist yet.
- **`applicationEvents.opened()` stays in `OfferDetail.ngOnInit`, and the split made it cheaper.** The component is now
  created once and reused across ids, so the whole board is fetched once per session instead of once per offer opened.
  The accepted consequence: a status changed elsewhere mid-session is not picked up.
- **`/offers/:id` is a redirect into `/shortlist/:id`.** A `:name` in `redirectTo` is substituted from the matched
  segments, so old links and bookmarks keep working, and there is exactly one detail view in the code afterwards.
- **`lg-page-header` takes a `heading` of `h1` or `h2`.** The detail column renders inside another screen; a second `h1`
  claimed to be the page while the screen's own title stood beside it, and shouted at 2rem next to a scan column.
- **The shortlist's heading sits above both columns, like every other screen's.** It used to live inside the left
  column, on the argument that it belongs to the list; it read as a column label rather than as the name of the screen,
  and it was the one screen whose title was somewhere else. The count and the archive toggle came up with it — the count
  is read on every filter change and the toggle decides which *set* the screen is showing, which is a statement about
  the screen. The filters stayed down in the column: they filter the list and nothing else, and a search field spanning
  the page while acting on a 34rem column is a false affordance.
- **The offer card carries no description teaser.** Two clamped lines of somebody else's prose under a title that
  already says what the offer is, on the one surface that is scanned twenty at a time — it cost about a third of a
  card's height for a sentence the detail column renders properly a few hundred pixels to the right.
  `shared/text/plain-text.ts` went with it: the card was its only consumer, and a tested util nothing calls is still
  dead code.
- **`lg-markdown` pushes every heading two levels down.** The advert's text is somebody else's and `#` renders an
  `<h1>`, so an ad that opened with its own title claimed the page's heading. Two levels and not one, because the text
  sits in a panel whose own heading is an
  `<h2>`.
- **The summary panel above the advert is the source's own `description`, not a summary this tool writes.** It appears
  only when the enriched `full_text` is on screen — without it the advert panel *is* that description, and the same
  paragraph twice says nothing the second time. The caption says where it came from, because the field is easy to
  mistake for something generated here.

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
- **The shortlist opens its first offer by itself, and only where both columns fit.** An empty right column beside a
  full list is a page waiting for a click it does not need: the first entry is the highest-scoring one the current
  filters produced. Below the stylesheet's own `72rem` the detail *replaces* the list, so auto-selecting there would
  answer "show me the shortlist" with a single offer — the condition is `matchMedia`, which is a media query and not a
  rendering-lifecycle API and therefore answers correctly in a backgrounded tab, unlike a `ResizeObserver`. The
  breakpoint is stated once on each side and tied together by a comment; jsdom has no `matchMedia` at all, so the guard
  is also what keeps the existing specs unaffected. `replaceUrl`, because the two URLs are the same screen once both
  columns fit and a history entry between them makes the back button undo a selection nobody made. Only when nothing is
  selected, so a filter change never moves the reader off the offer they are reading.
- **The dashboard says how many offers the last run judged, and which judge answered.** The per-run count and not the
  standing shortlist: a run judges what is stale, so zero is the normal outcome of a pass that found nothing new, and
  the catalog says that in words rather than leaving a bare 0 to read as scoring having stopped working — the same
  reading
  `IngestReport.merged` had to be protected from. The model comes from the recorded row alone, because an `IngestReport`
  carries none; a run this browser started therefore shows the count without a scale until the recorded row catches up.
  Two catalog keys rather than one sentence with an optional tail, because "· model null" is worse than a sentence that
  does not mention one.

- **Loading more is a sentinel, not a button**, because the list is read by scrolling; its
  `IntersectionObserver` is armed only after the first render, since one attached before layout fires immediately
  against a zero-sized box and asks for page two before page one is drawn. It is measured against the pane it was given
  rather than the window — see § *The split views*. **It cannot be verified in a backgrounded tab** — Chrome suspends
  the observer there, and the measurement comes back as a confident "nothing loaded". Measured through the Interceptor
  skill's `Tools/VerifyViewport.ts`: 50 offers, then 100 after scrolling.
- **A run opens its `pipeline_run` row when it starts, not when it ends.** The row says
  `RUNNING` and carries zeros, so it claims nothing — which is what the old "written last"
  placement was protecting. What it buys: `source_run` has no run id, so its rows are addressed by time, and the
  reported run's window is closed by the **next run's
  `started_at`**. Without a row at the start there was no upper bound, and a pass in flight put its rows inside the last
  finished run's window — measured, every source listed twice. The bound is `started_at` and never `finished_at`,
  because the batch collector moves the latter forward. `lastRun()` reports finished runs only; a `RUNNING` row on the
  dashboard would be zeros under the heading "last run".
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
                                  pipeline, review, sources, rules. Shortlist, pipeline
                                  and review are split views — § The split views
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

13. ✅ **Split views** — reading an offer used to cost the list. The shortlist, the board and the review queue each keep
    their list on the left and open what is selected on the right, in two independently scrolling columns under a screen
    bounded to the viewport. The shortlist and the board open a child route on a real component; the review holds the
    file name in a query parameter, because its document is already in the store the screen reads. `/offers/:id`
    redirects into the shortlist's split view, so there is one detail view in the code. What that cost and what it
    enforces is in § *The split views*.

## Traps that have already cost money

- **Reformatting an applied migration takes every deployed database down.** Flyway hashes the file's bytes, so
  realigning a column list or moving a `(` to its own line changes the checksum of a migration that ran months ago, and
  the application refuses to start with a mismatch per version rather than with anything naming the commit. Measured:
  one
  "Reformat Code" across the repository touched thirteen of sixteen migrations and stopped the microk8s deployment dead.
  The repair is to restore the files, not to repair the database, because the checksum has to match on every environment
  at once.
  `.editorconfig` switches the IntelliJ formatter off for `db/migration/*.sql` for exactly this reason.
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
- **A componentless leaf route is refused outright.** `{ path: ':name' }` with neither component, `loadComponent`,
  `redirectTo`, `children` nor `loadChildren` throws `NG04014`
  when the router config is validated — which happens when the `Router` is constructed, so every spec that merely
  injects it fails, far from the route that caused it.
- **An author `display` on a popover keeps it open forever, and every API you would ask says it is closed.** What hides
  a closed popover is the UA rule `[popover]:not(:popover-open) { display: none }`, which carries no `!important`, so a
  `display: flex` on the panel's own class beats it. The panel then stands open on the page while `:popover-open`
  reports `false`, `aria-expanded` reports `"false"` and the click still toggles the state correctly. Put `display` on
  `:popover-open` and nowhere else. Shipped exactly that way once and found by a person looking at the screen: the
  screenshots showed it open, the probe asked the API, and the screenshot was the one that got explained away as a
  rendering artifact.
- **jsdom has no `document.scrollingElement`, and it is typed `Element | null`.** It arrives `undefined`, walks straight
  through a `!== null` guard and takes down every spec of the screen that reads it with "Cannot set properties of
  undefined" — ten at once, from inside an effect, far from anything that names scrolling. The guard has to be truthy.
  Same family as the next one, and found the same way.
- **jsdom implements `scrollTop` but not `Element.scrollTo`.** A scroll reset written as
  `scrollTo({ top: 0 })` passes `tsc`, works in the browser, and takes down every spec that renders the component with
  `scrollTo is not a function` from inside an effect.
- **Safari intermittently keeps the folded height of an unfolded advert.** Measured on the page: `max-height: none`,
  `overflow: visible`, no mask, and the box still exactly 390px, the clamp's own value, with the text running on behind
  the panels below it. Six isolated variants of the structure — scroll pane, grid, spanning panel, mask, the whole
  height chain — were all correct in the same Safari, and the same page measured correctly a minute later.
  `OfferDetail.relayoutAd` detaches the box and reads a metric off it after the toggle. It is a workaround on an
  observation, not on a reproduced cause, and it says so.
- **ImageMagick renders SVG with its own parser and drops paths containing arcs** unless
  `rsvg-convert` is on PATH as its delegate. The first favicon looked broken for that
  reason alone, with the geometry perfectly correct.

## Open

- **CI.** The tooling baseline is in place (`.editorconfig`, ESLint, Prettier,
  Stylelint), but no pipeline runs it yet.
- Which folder in the IMAP mailbox the newsletter lands in — deployment detail, and it
  does not belong in a committed file.
- **`lg-page-header` has no step below `h2`.** The board's reading column made this visible — the advert's title wrapped
  to six lines at 30rem — and `--lg-detail-w` going to 40rem bought enough width that it stopped being urgent rather
  than fixing it. A parent's styles do not reach a component host the router created, so the fix is a third heading
  level on the component itself.

## Settled

- **License: Apache-2.0.** `LICENSE` and `NOTICE` at the root, SPDX headers on the Java sources. They used to be
  enforced by Spotless; Spotless is off for now (the reason is in
  `backend/build.gradle.kts`), so a new Java file needs its header copied by hand until it comes back.
- **The repository is `codeministry/leadgen`**, which is why the Java package
  `de.codeministry.leadgen` stays as it is.
- **No Helm chart in the repository.** Docker Compose is the supported way to run this;
  a chart is a later phase and the README no longer claims one.
