---
phase: climbing
progress: 83/83
task: "Acquisition tool: collect, filter, enrich and package project offers"
slug: lead-generation
started: 2026-09-01T11:30:00Z
updated: 2026-09-02T01:10:00Z
---

# lead-generation — Ideal State Artifact

## Problem

Project offers for a freelancer arrive as newsletters, portal feeds and direct enquiries, and the same project reaches the mailbox through several agencies at once. Reviewing them costs an hour a day and produces nothing durable; assembling the documents costs it again for every single application. The measured corpus makes the size of it concrete: 14 newsletter mails carry **1289 offers**, of which a deterministic filter would discard **83.5 %** without a human ever seeing them, and **12.3 %** are the same project appearing under different titles. What is not automatable is the decision to apply — and that is precisely the part that currently gets the least attention, because the sorting eats the time.

## Vision

Morning, one page. Fifteen offers instead of a hundred, each with the reason it survived and the reason it scored what it scored, duplicates already collapsed into one entry that names the three agencies advertising it. The two that matter already have a folder next to them: cover letter written against *this* ad in the ad's language, the right CV attached, the original text archived. Nothing has been sent. The surprise is not that the machine sorted the mail — it is that a decision that used to take an hour now takes ninety seconds, and that the ninety seconds are spent on judgement rather than on scrolling.

## Out of Scope

No automatic sending, ever. Both outputs are files: the digest as text or HTML, the package as a folder. The configuration models no transport, recipient or channel either, because a schema that has a place for one is an invitation to fill it. No per-offer CV tailoring: the PDFs are fixed and chosen by the language of the ad and nothing else. No CRM, no invoicing, no time tracking. No multi-user tenancy; this is a single-operator tool. No scraping of portals that forbid it — `robots.txt` is respected, and a portal that refuses is simply a portal without enrichment. No attempt to guess a rate the source did not state.

## Language

Terms enter this section only after they have actually caused a confusion in the work.

- **application.yaml** — Spring's and only Spring's: it wires the *process* (datasource, ports, where the config directory is). _Avoid_: using it for the tool's own configuration, which is **pipeline.yaml** behind `PipelineConfig`. The two shared a name until 2026-09-01, and a stack trace naming it could mean either file — which is the confusion that produced this entry.
- **default vs override** — a configuration file exists twice: the committed default on the classpath under `/leadgen/`, and optionally a file of the same name in `config/` that replaces it whole. _Avoid_: "the config file" without saying which layer; the startup log names one per file.
- **duplicate** — one *project* advertised by several portals. Collapsing them is deduplication (F5). _Avoid_: using it for one *listing* seen in two mails, which the upsert on `(source_id, external_id)` collapses and which is not deduplication. The 1289-versus-1280 gap is the second kind; the 159 are the first.
- **written** — rows an upsert touched, insert or update alike. _Avoid_: "stored", which reads as "new rows" and made a second ingest run look like it had doubled the archive when it had changed nothing.
- **offer** — one listing as one source stated it, before any judgement. _Avoid_: using it for the merged cluster after dedupe, and for the scored shortlist entry; those are distinct things that will need distinct names when F5 and F8 land.

## Principles

- **Rules before model.** Anything a deterministic rule can decide is decided deterministically and for free. Without a language model the tool must still run — weaker, never broken.
- **Configuration over deployment.** A new source of offers is a block of YAML. If adding one requires a code change, the abstraction is in the wrong place.
- **A number the source states is worth more than a number inferred.** Coverage is measured before it is relied on; the enrichment stage exists because 0.0 % of the corpus states a rate, not because it seemed prudent.
- **Silence is the enemy.** Every mechanism whose failure mode is "slightly fewer results" gets an explicit check, because that failure looks exactly like a quiet market.
- **The human's mailbox is not the tool's workspace.** Whatever the tool reads, the owner reads too, on another device, first.

## Constraints

- Java 21 and Spring Boot 4 in `backend/`, Angular 22 zoneless with `@ngrx/signals` in `frontend/`, one Gradle build bracketing both. bun is the package manager; never npm or npx.
- Flyway owns the database schema; nothing else creates or alters it.
- The repository is going public. No portal name, no mail provider, no newsletter, no model name and no personal datum in code or in tracked configuration. The configuration that ships names every value as a `${PLACEHOLDER}`; the values live in `.env` and anything individual beyond them in `config/`, both gitignored.
- Configuration comes in two layers, the same as Spring's own: working defaults on the classpath in the jar, an external directory overriding them file by file. The tool runs on a fresh clone with nothing configured.
- The subscriber's mail address must not leave the machine. Newsletter links are tracking proxies carrying it; anything derived from an unwrapped link would carry it into the database and into every exported package.
- Progress through a mailbox is tracked by `UIDVALIDITY`/`UID` and never by seen/unseen flags.
- Everything in the repository is English — code, comments, documentation, tests, log output. The offers are German and the cover letters are German; that is *content*, it lives in `config/`, and it is selected by the language of the ad.
- The DA never commits. Work is left uncommitted and the commit is offered.

## Goal

A single operator drops mailbox credentials and a profile into `config/`, and every morning receives a scored shortlist of roughly fifteen offers drawn from over a hundred, deduplicated across portals, enriched with the rate and full text the newsletter omitted, with a ready-to-send application package assembled for everything above the shortlist threshold — and sends nothing without deciding to.

## Not yet specified

- fog: how a merged duplicate cluster is named and addressed once it is no longer a single offer — F5 and F8 both need the distinction, and the Language section already records that the word `offer` is carrying two meanings.
- fog: how the frontend writes. The shell, the shortlist and the other four screens now exist and read, but every write is unspecified — moving a card between pipeline lanes, recording that a mail went out, editing a weight, uploading a Markdown file as a source. The first of those is also the first write endpoint in the application, and `security.auth` is `none`.

## Test Strategy

| isc | type | check | threshold | tool | anchors_to |
|---|---|---|---|---|---|
| ISC-1 | bash | grep the tracked tree for the anonymized terms and the address | 0 hits | rg | `.gitignore`, `docs/SAMPLE-ANALYSIS.md` |
| ISC-2 | bash | `git add -An .` and match against the ignore list | 0 hits | git | `.gitignore` |
| ISC-3 | bun-test | assert every extracted URL over the corpus | 0 of 1289 | JUnit | `SampleCorpusAcceptanceTest` |
| ISC-4 | bash | grep Java sources for selectors and emoji prefixes | 0 hits | rg | `backend/src/main/java` |
| ISC-58 | bash | grep the tracked tree for known product and vendor names | 0 hits | rg | `resources/leadgen/`, `.env.example` |
| ISC-5 | bash | `./gradlew check` | exit 0 | Gradle | root build |
| ISC-6 | manual | licence file present and referenced | | review | `LICENSE` |
| ISC-7 | bash | pipeline run on a pushed branch | red blocks merge | CI | not yet authored |
| ISC-8 | curl | `docker compose up` then probe api, web root and a client route | 3× HTTP 200 | curl | `docker-compose.yml` |
| ISC-9 | screenshot | open the page in a real browser and read the API-sourced value | value rendered | Interceptor | `frontend/src/app/app.html` |
| ISC-10 | bun-test | query `information_schema.tables` after startup | 4 tables | Testcontainers | `LeadGenerationApplicationTests` |
| ISC-11 | bun-test | test context boots without a `.env` | green | JUnit | `backend/build.gradle.kts` |
| ISC-12 | bun-test | resolve from repository root and from `backend/` | same directory | JUnit | `ConfigProperties` |
| ISC-13 | bash | `bun run lint` with a deliberate cross-layer import | exit 1 | ESLint | `frontend/eslint.config.mjs` |
| ISC-59 | bash | start via `bootRun` and via `java -jar` from the repository root, nothing exported | same values, both log the file | Bash | `PlaceholderResolver` |
| ISC-60 | bash | read the startup log for the JDBC URL | host, port and database named | Bash | `DatasourceBanner` |
| ISC-61 | bash | change `POSTGRES_PORT`, run `docker compose config` | published follows, target stays 5432 | Compose | `docker-compose.yml` |
| ISC-14 | bun-test | load the shipped examples and assert bound values | green | JUnit | `ConfigLoaderTest` |
| ISC-15 | bun-test | rename a key, expect the load to fail naming it | message contains key | JUnit | `ConfigLoaderTest` |
| ISC-16 | bun-test | write an invalid file, assert the snapshot is unchanged | same instance | JUnit | `ConfigWatcherTest` |
| ISC-17 | bun-test | change a file, poll twice | new value | JUnit | `ConfigWatcherTest` |
| ISC-18 | bun-test | disable hot reload, change a file, poll | snapshot unchanged | JUnit | `ConfigWatcherTest` |
| ISC-19 | bun-test | set `apply_after: hard_filter` | load rejected | JUnit | `ConfigLoaderTest` |
| ISC-20 | bun-test | three malformed source blocks | 3 distinct messages | JUnit | `ConfigLoaderTest` |
| ISC-21 | bun-test | materialize the classpath defaults into a temp dir and load | green | JUnit | `ConfigFixtures` |
| ISC-55 | bun-test | load with a directory that does not exist | every file from classpath | JUnit | `ConfigLoaderTest` |
| ISC-56 | bun-test | edit one external file, load, read the changed value | external wins | JUnit | `ConfigLoaderTest` |
| ISC-57 | bun-test | `rules.path` carrying a directory | only the file name used | JUnit | `ConfigLoader.fileName` |
| ISC-22 | bun-test | extract the corpus, compare per-document to the subject count | 1289, 14 of 14 | JUnit | `SampleCorpusAcceptanceTest` |
| ISC-23 | bun-test | count non-null per field | exact 8 counts | JUnit | `SampleCorpusAcceptanceTest` |
| ISC-24 | bun-test | fixture with a missing company and a missing location | no field shifted | JUnit | `ExtractionTest` |
| ISC-25 | bun-test | assert the source's fallback and that no model client is invoked | `none` | JUnit | `ExtractionTest` |
| ISC-26 | bun-test | ingest twice, compare row count | unchanged | Testcontainers | `IngestServiceTest` |
| ISC-27 | bash | grep Java for `job-card`, `job-title`, the emoji prefixes | 0 hits | rg | `HtmlBlockExtractor` |
| ISC-53 | bun-test | ingest the corpus, compare announced to extracted per document | 14 of 14 | JUnit | `IngestService.check` |
| ISC-54 | bun-test | a source with `extraction.inherit`, and one inheriting an inheritor | resolved / rejected | JUnit | `ConfigLoader.resolveInheritance` |
| ISC-28 | bun-test | free-form enquiry through the llm strategy | offer extracted | JUnit | not yet authored |
| ISC-29 | bun-test | deliver the fixture over IMAP, compare to the file path | identical offers | GreenMail | `ImapSourceConnectorTest` |
| ISC-30 | bun-test | read, then inspect the message flags | `\Seen` absent | GreenMail | `ImapSourceConnectorTest` |
| ISC-31 | bun-test | read, commit, read again | 0 documents | GreenMail | `ImapSourceConnectorTest` |
| ISC-32 | bun-test | bump the stored `UIDVALIDITY`, read | folder re-read | GreenMail | `ImapSourceConnectorTest` |
| ISC-33 | bun-test | deliver one matching and one non-matching mail, commit | cursor at the matching UID | GreenMail | `ImapSourceConnectorTest` |
| ISC-34 | bun-test | one unreachable source beside a file source | file source still runs | JUnit | `IngestService` |
| ISC-35 | bun-test | advance a fixed clock past the interval | one run triggered | JUnit | not yet authored |
| ISC-36 | bun-test | `match_all: true` with no sender filter | every mail read | GreenMail | not yet authored |
| ISC-37 | bun-test | two listings of one project from different portals | one entry, two sources | JUnit | `DeduplicationServiceTest` |
| ISC-38 | bun-test | fingerprint collisions over the corpus, and n offers over k fingerprints | 159; n-k attached | JUnit | `SampleCorpusAcceptanceTest`, `DeduplicationServiceTest` |
| ISC-39 | bun-test | insert newest first, then add a late arrival, then run twice | first-seen primary, stable | JUnit | `DeduplicationServiceTest` |
| ISC-40 | bun-test | titles differing by more than case, punctuation and the gender suffix | not merged | JUnit | `DeduplicationServiceTest` |
| ISC-41 | bun-test | run the filter over the corpus | 239 of 1289, 18.5 % | JUnit | `HardFilterCorpusTest`, `docs/samples/simulate_filter.py` |
| ISC-42 | bun-test | per-stage removal counts sum to the total | exact, all seven | JUnit | `HardFilterCorpusTest` |
| ISC-43 | bun-test | offer with no stated remote share | survives, flagged | JUnit | `HardFilterTest` |
| ISC-44 | bun-test | offer far below the rate floor | survives, rate never read | JUnit | `HardFilterTest` |
| ISC-45 | bun-test | fetch a stubbed ad page | 7 fields extracted | WireMock | `EnrichmentServiceTest` |
| ISC-46 | bun-test | fetch returning 403 | offer kept, marked incomplete | WireMock | `EnrichmentServiceTest` |
| ISC-47 | bun-test | disallowed path and a warm cache | 0 requests | WireMock | `EnrichmentServiceTest`, `RobotsPolicyTest` |
| ISC-48 | bun-test | score with a stubbed judge, read the reasons back | one per factor, weights respected | WireMock | `ScoringWithAModelTest` |
| ISC-49 | bun-test | render the digest from a seeded database | file written, both bands present | JUnit | `DigestServiceTest` |
| ISC-50 | bun-test | run the pipeline with no LLM key | completes, unscored, reasons kept | JUnit | `ScoringWithoutAModelTest` |
| ISC-51 | bun-test | package an offer above the threshold | 4 files, ad's language | JUnit | `PackagingServiceTest` |
| ISC-52 | bun-test | read the tree for send paths and transport keys, back and front | 0 hits | JUnit | `NothingIsSentTest` |
| ISC-62 | bash | add a hex literal under `src/app`, run `bun run lint:css` | exit 1 naming the rule | Stylelint | `.stylelintrc.json` |
| ISC-63 | bash | grep the built stylesheet for theme selectors | both `lg-*`, 0 stock | rg | `frontend/src/styles.css` |
| ISC-64 | screenshot | six routes x 320/768/1440, page-level scroll width | 0 overflow each | Interceptor | `layout/app-shell` |
| ISC-65 | screenshot | enumerate tabbable elements and their accessible names | 0 unnamed | Interceptor | `layout/`, `shared/` |
| ISC-66 | bash | index of the inline script vs the stylesheet links in the built `index.html` | script first | rg | `frontend/src/index.html` |
| ISC-67 | bun-test | render a score of `null` | banded unscored, no zero | Vitest | `shared/score/score.spec.ts` |
| ISC-68 | bun-test | render the rail over the measured corpus | 1,289 in, 213 out, 16.5 % | Vitest | `shared/funnel-rail/funnel-rail.spec.ts` |
| ISC-69 | bash | count files under `core/fixtures/` against the removal marker | equal | rg | `frontend/src/app/core/fixtures/` |
| ISC-70 | bun-test | render the shortlist with no query parameters at all | renders, cards present | Vitest | `features/shortlist/shortlist-page.spec.ts` |
| ISC-71 | bun-test | open an application twice around a status change | one row, status kept | JUnit | `ApplicationServiceTest` |
| ISC-72 | bun-test | move backwards through the states, then read the history | accepted, every change kept | JUnit | `ApplicationServiceTest` |
| ISC-73 | bun-test | a follow-up on its day, on closing, and cancelled | due, dropped, removed | JUnit | `ApplicationServiceTest` |
| ISC-74 | bun-test | PATCH an unknown status and an unknown id | 400 and 404 | MockMvc | `ApplicationControllerTest` |
| ISC-75 | bun-test | load the board and read the lanes it grouped by | server's lanes, not a constant | Vitest | `applications.store.spec.ts` |
| ISC-76 | bun-test | change a status and read the row back | server's answer, not the request | Vitest | `applications.store.spec.ts` |
| ISC-77 | bun-test | render the picker on a card that is not in the first state | shows the state it is in | Vitest | `status-picker.spec.ts` |
| ISC-78 | interceptor | pick a state on the board, clear a follow-up on the detail | both rows written | psql | live browser |
| ISC-79 | bun-test | read a markdown file with frontmatter through the file source | eight fields, body as description | JUnit | `MarkdownExtractionTest` |
| ISC-80 | bun-test | a proxy link inside an uploaded file | unwrapped, no address | JUnit | `MarkdownExtractionTest` |
| ISC-81 | bun-test | read a file that is nothing but a pasted ad | no offer at all | JUnit | `MarkdownExtractionTest` |
| ISC-82 | bun-test | an offer with no url, read twice | same external id | JUnit | `MarkdownExtractionTest` |
| ISC-83 | bash | run the suite and look for stray directories | none outside the config dir | git status | whole repository |
| ISC-84 | interceptor | upload a document and review it before it enters | fields correctable, duplicate named | live browser | `ReviewCard`, `ManualUploadServiceTest` |
| ISC-85 | bun-test | upload a wrong extension, an oversized file, a traversing name | all three refused | JUnit | `ManualUploadServiceTest` |

## Features

### F0 · Cross-cutting

**Why:** The invariants that hold no matter which pipeline stage is being built, and each of which fails silently when broken — a leaked address is invisible until the repository is public, and a wired-in portal name is invisible until someone else tries to use the tool.

- [x] ISC-1: No tracked file contains the subscriber's mail address, a portal name, a mail provider or a newsletter sender; the anonymized forms are `<newsletter-sender>`, `<aggregator-host>` and `portal-a`…`portal-f`.
- [x] ISC-2: `config/`, `.env`, `docs/samples/emails/` and everything derived from them are gitignored, and a dry-run `git add` of the whole tree lists none of them.
- [x] ISC-3: Anti: no URL persisted by any pipeline stage contains `@`, `email=` or `%40`.
- [x] ISC-4: Anti: no CSS selector, sender address, folder name or portal name appears in Java source.
- [x] ISC-58: Anti: no committed file names a concrete service, product, vendor or model as a configuration value, **including as a default** — transports, providers and portals are kinds, and which one is used lives in `.env`.
- [x] ISC-5: `./gradlew check` runs both modules — backend tests and frontend lint plus tests — in one invocation and is green.
- [ ] ISC-6: A licence is chosen, added as `LICENSE`, and referenced from `README.md`.
- [ ] ISC-7: CI runs `./gradlew check` on every push and blocks a merge on red.
- [x] ISC-59: One credentials file, `.env`, and every value in it reaches the application identically whether the process was started by Gradle, by an IDE run configuration or as a jar.

### F1 · Monorepo and operation

**Why:** Everything downstream is written against a stack that has to be provably real first; a skeleton that compiles but has never served a page is a skeleton that hides its own gaps.

- [x] ISC-8: `docker compose up --build` brings postgres, api and web up, with the API answering `GET /api/status` and nginx serving the SPA with a client-route fallback.
- [x] ISC-9: The frontend reaches the backend through the nginx proxy and renders a value that came from it.
- [x] ISC-10: Flyway executes its migrations against a real Postgres at startup; a test asserts the resulting tables exist rather than that the context merely loaded.
- [x] ISC-11: The backend reads the untracked `.env` on `bootRun` and never through `spring.config.import`, so no test context depends on a file absent from the repository.
- [x] ISC-12: `leadgen.config-dir` resolves correctly whether the process is started by Gradle (working directory `backend/`), by an IDE (repository root) or from a jar.
- [x] ISC-13: The layering `shared → core → layout → features` is enforced by lint and fails the build on a cross-layer import.
- [x] ISC-60: The effective JDBC URL and user are logged at startup, so a connection to the wrong database is identifiable from the log alone.
- [x] ISC-61: `POSTGRES_PORT` in `.env` drives the published port, and the container side of the mapping stays 5432 whatever the host side is.

### F2 · Configuration layer

**Why:** Every later stage reads its rules from here, so a configuration error that passes quietly becomes a pipeline that filters the wrong things without anyone noticing.

- [x] ISC-14: `application.yaml`, `matching-rules.yaml` and `sources.yaml` are read, `${VAR}`/`${VAR:default}` resolved, bound and validated into one immutable snapshot.
- [x] ISC-15: An unknown key in any of the three files fails the load, naming the key.
- [x] ISC-16: An invalid configuration at startup is fatal; an invalid configuration at reload keeps the last good snapshot, logs the reason and leaves the running process serving.
- [x] ISC-17: A change on disk is applied within two poll cycles, and only after the file has stopped changing.
- [x] ISC-18: `rules.hot_reload: false` means a change is detected, logged and deliberately not applied.
- [x] ISC-19: `hard_filters.rate.apply_after` is rejected at load time unless it is `enrichment`, with the measured reason in the message.
- [x] ISC-20: A source naming an undeclared connection, a duplicate id, or an enabled mailbox source without credentials each fail the load with a message naming the fix.
- [x] ISC-21: The loader tests run against the shipped defaults themselves, so a broken default fails the build rather than a user's first start.
- [x] ISC-55: The tool starts and runs with no external configuration directory at all, reading every file from the classpath.
- [x] ISC-56: A file present in the external directory replaces the classpath default of the same name, and the startup log names which layer each file came from.
- [x] ISC-57: Anti: a path in `pipeline.yaml` never resolves to a file outside the two layers; only its file name is used.

### F3 · Ingest and extraction

**Why:** This is where the measured baseline is either reproduced or quietly missed; every number the rest of the tool is planned against comes from here.

- [x] ISC-22: 14 sample mails yield exactly 1289 offers, and the count each subject announces matches per mail in all 14.
- [x] ISC-23: Field coverage matches the reference measurement exactly — title 1289, url 1289, portal 1289, published 1289, tags 1289, description 1288, location 1283, agency 1170.
- [x] ISC-24: Meta fields are addressed by their prefix, so an offer missing one does not shift the others.
- [x] ISC-25: Extraction of this source involves no language model at all (`fallback: none`).
- [x] ISC-26: Re-reading the same document writes every offer again and adds no row.
- [x] ISC-27: Anti: no selector, prefix or date format used by extraction is written in Java.
- [x] ISC-53: Every document that states how many offers it contains is checked against the extraction, and a mismatch is logged loudly without discarding what did come through.
- [x] ISC-54: A second source reuses another's extraction rules by name rather than by copy, and a chain of two is rejected.
- [ ] ISC-28: A source with `extraction.strategy: llm` extracts an offer from free-form prose, and the tool still runs with the model unavailable.

### F4 · Mailbox connector

**Why:** The mailbox belongs to the operator, not to the tool; a connector that marks mail read or loses its place turns a convenience into a liability on the device they actually use.

- [x] ISC-29: Mails are read from IMAP and produce the same offers as the same mails read from disk.
- [x] ISC-30: Anti: no message is ever flagged `\Seen`, verified against a real IMAP server.
- [x] ISC-31: A run with nothing new returns no documents, despite `getMessagesByUID(start, LASTUID)` returning the highest-UID message regardless of `start`.
- [x] ISC-32: A changed `UIDVALIDITY` resets the cursor and the folder is read from the start.
- [x] ISC-33: The cursor advances only after the offers are stored, and only over messages the selector actually matched.
- [x] ISC-34: One unreachable source does not stop the other sources in the same run.
- [ ] ISC-35: A scheduled run executes on the configured interval without manual triggering.
- [ ] ISC-36: Dedicated mode (`match_all: true`) reads a folder that holds nothing but the newsletter, with no sender or subject filter.

### F5 · Deduplication

**Why:** The same project arrives up to eight times across three portals; without collapsing them the shortlist is mostly the same offer, and the operator learns to distrust it.

- [x] ISC-37: Exact-fingerprint duplicates are merged, and the merged entry names every source it came from.
- [x] ISC-38: Deduplicating the sample corpus collapses 159 offers, matching the reference measurement. Two halves: the corpus assertion counts fingerprint collisions without a database, and the clustering test fixes the identity that turns that count into rows — n offers over k fingerprints leave exactly n − k attached.
- [x] ISC-39: The merge keeps the first-seen offer as primary and attaches the others, so no source is lost. It holds whatever the insertion order, for a listing that arrives on a later run, and across repeated runs.
- [x] ISC-40: Anti: only an exact match of the normalized title merges. Normalisation removes casing, punctuation and the gender suffix and nothing else, so a title that merely resembles another stays its own offer. **The honest limit is the opposite case:** two genuinely different projects that happen to share a title do merge, and nothing available before enrichment separates them — see the Decisions entry, where the obvious fix was measured and is worse.

### F6 · Hard filter

**Why:** This is the stage that turns a hundred offers into fifteen, and it is the one whose failure is indistinguishable from a quiet market — which is why it has a measured target rather than a plausible one.

- [x] ISC-41: The hard filter passes **239 of the 1289 sample offers, 18.5 %**, matching `docs/samples/simulate_filter.py` exactly; a deviation is a bug and not a matter of taste. The target used to read 16.5 % and moved because the reference was wrong, not because the implementation missed it — see the Learning entry. (after: ISC-37)
- [x] ISC-42: Each of the five filter stages reports how many it removed, so a change in the total is attributable.
- [x] ISC-43: An offer whose remote share is unknown survives and is flagged, rather than being discarded.
- [x] ISC-44: Anti: `min_hourly_eur` has no effect at this stage.

### F7 · Enrichment

**Why:** The newsletter states no rate, no duration and no workload in any of 1289 offers; without fetching the original ad the scoring stage would be judging a generated two-line summary.

- [x] ISC-45: For a filtered offer, the original ad is fetched and rate, duration, workload and full text are extracted from it. (after: ISC-41)
- [x] ISC-46: A failed or forbidden fetch leaves the offer in the pipeline marked incomplete, and never discards it.
- [x] ISC-47: Fetching respects `robots.txt`, the configured rate limit and the cache TTL, and a second run within the TTL issues no request.

### F8 · Scoring and digest

**Why:** The reason an offer scored what it scored is the part the operator actually reads; a number without a reason gets ignored within a week.

- [x] ISC-48: Each surviving offer receives a score with the weights from `matching-rules.yaml` and a stated reason per contributing factor. The weight table bounds the model rather than the other way round: an invented factor is dropped and a judged one is clamped to its weight. (after: ISC-45)
- [x] ISC-49: The daily digest is rendered to a file as text or HTML, lists the shortlist and the review band, and is produced without a frontend.
- [x] ISC-50: Anti: with no LLM key configured, the pipeline still runs and produces an unscored shortlist rather than failing. It keeps the deterministic reasons too — unscored is not "nothing known", only "no comparable total".

### F9 · Application package

**Why:** The hour saved on sorting is given back if assembling the documents still takes twenty minutes per application.

- [x] ISC-51: An offer above the shortlist threshold produces a folder with a cover letter in the language of the ad, the matching fixed CV, the archived original and a `meta.json`.
- [x] ISC-52: Anti: nothing is sent; the tool has no send path at all, and the configuration models no transport, recipient or channel either. Enforced by a test that reads the repository, because this is the kind of thing that arrives one convenient afternoon — and packaging, where the folder is finished and the contact is in `meta.json`, is exactly where it would arrive.

### F10 · Frontend

**Why:** The pipeline's whole value is a reduction — over a thousand offers to roughly fifteen — and until something shows that reduction with the reason behind each number the operator is trusting a list they cannot check. Every failure in this feature is silent: a colour that fails contrast, a class that never reaches the stylesheet, a theme that flashes, a page that scrolls sideways on a phone.

- [x] ISC-62: Every colour literal lives in `src/styles.css`, the one file `color-no-hex` exempts; a hex anywhere under `src/app` fails the lint.
- [x] ISC-63: The two themes `lg-light` and `lg-dark` are the only ones in the built stylesheet; daisyUI's stock light and dark do not ship.
- [x] ISC-64: No screen scrolls the page sideways at 320, 768 or 1440 px. A container with its own `overflow-x` — the kanban board, the wide tables — still may.
- [x] ISC-65: Every focusable control has an accessible name, and a name never depends on an element nested inside the control.
- [x] ISC-66: The stored theme is applied before the stylesheet resolves, so the page never paints in the wrong theme and then corrects itself.
- [x] ISC-67: An unscored offer renders as unscored rather than as zero, because the pipeline still produces a shortlist with no language model.
- [x] ISC-68: The funnel rail reproduces the measured baseline exactly: 1,289 extracted, seven stages, 239 left, 18.5 %.
- [x] ISC-69: Every fixture file carries the same removal marker, so the temporary data is one grep away from being found rather than quietly becoming permanent.
- [x] ISC-70: The shortlist renders with no query parameters present at all.

### F11 · Manual status capture

**Why:** The tool never sends, so it cannot observe what happened to a mail: whether it went out, whether anyone replied, whether the project was lost to a cheaper bid. The board is the only place that state exists, which makes keeping it comfortable to update worth more than keeping it strict.

- [x] ISC-71: An application opens once when its package is built and a later packaging run never resets a status the operator has already moved on.
- [x] ISC-72: Any transition the operator reports is accepted, and every status change is kept as an event — a single mutable row cannot answer "when did I send this" after the second correction.
- [x] ISC-73: A follow-up is due on its day, is dropped when the application closes, and can be cancelled explicitly rather than only overwritten.
- [x] ISC-74: The endpoint refuses a status outside the eleven and answers 404 for an id that names nothing, so a typo fails at the door instead of reaching the database as a string nothing can read back.
- [x] ISC-75: The board groups by the lanes the endpoint states rather than by a copy of the enum, so adding a state never leaves the browser disagreeing with the server about where it belongs.
- [x] ISC-76: A change replaces the row with the server's answer, not with what was asked for — the service dates a send itself, and a locally patched row would disagree with the database until the next reload.
- [x] ISC-77: The picker shows the state the application is actually in, because a card reading "New" beside a badge reading SENT is wrong in the one place the operator looks to decide.
- [x] ISC-78: The whole path works in a real browser: picking a state on the board and clearing a follow-up on the detail both reach Postgres.

### F12 · Manual entry

**Why:** Offers arrive by routes the configured sources cannot see — a recruiter's mail, a Slack message, a portal without a feed. Those have to be able to enter the pipeline, or the shortlist quietly stops being the whole picture and nobody notices.

- [x] ISC-79: A Markdown file with YAML frontmatter becomes an offer on the next run, read deterministically and with no language model, through the file source that already exists.
- [x] ISC-80: A file pasted out of the newsletter loses the subscriber's address exactly as a newsletter mail does; the privacy boundary does not care how the document arrived.
- [x] ISC-81: A file that is nothing but a pasted ad produces no offer at all rather than one with no title, because `fallback: llm` does not exist yet and a silent half-offer is worse than none.
- [x] ISC-82: Re-reading the same document is free even when it states no URL, so uploading the same ad twice does not leave deduplication to clean up after it.
- [x] ISC-83: Anti: nothing the tool does creates a directory outside the configuration directory. A relative path read from the working directory is a directory that exists in one start path and not in another, and the tool's own test run demonstrated it.
- [x] ISC-84: An upload is reviewed before it becomes an offer: the extracted fields beside the source text, an answer to "is this already in the pipeline", and a correction that is written back into the file.
- [x] ISC-85: Anti: the upload endpoint cannot be made to write outside the inbox — extension allowlist, size limit, no path traversal — and `security.auth` is answered rather than left at `none`.

## Decisions

- **2026-09-01 — Portal and source names anonymized in the documentation.** The analysis docs named the aggregator, its sender and host, the mail provider and six portals in clear text. The repository is going public; they are now `<newsletter-sender>`, `<aggregator-host>` and `portal-a`…`portal-f`, with the real names in the gitignored `config/local/sources.yaml`. Every measured figure is unchanged.
- **2026-09-01 — The frontend is bracketed by plain Gradle `Exec` tasks calling bun, not by the Node Gradle plugin.** The plugin does not speak bun, and bun is the package manager everywhere here. `package.json` stays the single list of frontend commands.
- **2026-09-01 — JDBC replaces JPA.** The pipeline writes in batches and upserts with `ON CONFLICT`, which is one plain SQL statement against a schema Flyway owns. An ORM would have added a mapping layer over Postgres arrays for no gain. Flyway is now the only thing that touches the schema.
- **2026-09-01 — `rules.hot_reload` switches all three configuration files, not only the rules.** They are read and swapped as one snapshot; reloading one without the others would hand the pipeline a picture that never existed on disk.
- **2026-09-01 — Change detection polls timestamps instead of using `WatchService`.** For three files the efficiency argument is worth nothing, the watch service is native only on Linux, and on macOS the JDK falls back to polling with a ten-second default latency — the same mechanism with platform-dependent timing nobody can reason about.
- **2026-09-01 — (superseded the same day, see the Learning entry) Paths in `application.yaml` are file names resolved against the configuration directory.** They carried `config/local/` themselves, which breaks the moment the directory moves; inside the container it is `/config`. A path with the directory baked in is still accepted, resolved upwards from the working directory, because breaking an existing configuration over a style is not worth it.
- **2026-09-01 — Skills (`span.skill-pill`) are not modelled.** Dead end: the reference implementation extracts them, and the element does not occur in the corpus at all. Coverage 0 %.
- **2026-09-01 — Lombok for boilerplate, records for data.** `@Slf4j` and `@RequiredArgsConstructor` where the constructor is nothing but assignments; not where it does work, and not where parameters carry `@Value`. API types stay records, each in its own file. The configuration model keeps its nesting, which mirrors the YAML it describes.
- **2026-09-01 — The cursor is committed after the write, not after the read.** `SourceConnector.commit` exists for that alone.
- **2026-09-01 — One credentials file, and it is called `.env` because it cannot be called anything else for free.** Marcello's call to collapse `.env.local` plus a `.env` symlink. The name is forced by Compose: it substitutes the `${...}` in `docker-compose.yml` from `.env` and from nothing else — not from `env_file:`, which only injects into a container, and not from `COMPOSE_ENV_FILES` set inside a file. Both measured. Any other name costs a flag on every call or a symlink, and forgetting either silently applies the compose defaults.
- **2026-09-01 — The database host port defaults to 55432 and the container side is fixed at 5432.** A developer machine usually already holds 5432, and the resulting failure names the user and neither the host nor the database. Making the container side variable too publishes a host port forwarding to a port nobody listens on, which from a client looks exactly like no port at all.
- **2026-09-01 — Only `none` is an accepted `security.auth`, and it is fatal to write anything else.** Three modes were named in the configuration and none was implemented, which meant someone could write `basic`, believe the write endpoints were protected, and be wrong with no symptom at all. The mode is now rejected at load, and what actually stands in front of the endpoints is `server.address`, defaulting to `127.0.0.1`. Publishing that port anywhere else is the decision that would need an auth mode first, and it is now a decision somebody has to make on purpose.
- **2026-09-01 — The uploaded file is the state, and there is no staging table.** It is inspectable with `cat`, confirming is a move, a rejection is a delete, and the correction is written back into the document — so re-reading the same file later produces the same offer. A table would put half the pipeline's truth somewhere the source knows nothing about.
- **2026-09-01 — A relative source path names a place inside the configuration directory.** Resolved against the working directory instead, one configuration points at `backend/…` under `bootRun`, at the repository root in an IDE and at neither from a jar. It is the rule the four YAML files already follow, and the symptom of not following it was concrete: the test suite created `backend/config/inbox/pending` in the working tree, outside the gitignore that was written for `config/`.
- **2026-09-01 — Clearing the follow-up is a button, not an empty field.** The backend grew `clearFollowUp` so a reminder can be cancelled rather than only overwritten, and the form exposed it by emptying the date input. In Chrome that means deleting each segment of a native date widget in turn, which the operator will not do — the affordance existed in the API and was unreachable in the UI. Found by trying it in the browser, not by reading the code.
- **2026-09-01 — The status transitions are documented, not enforced.** Marcello's loop, not the tool's: every value is entered by hand about events the system never saw. A project can be lost before it was answered and a mistyped status has to be correctable without an argument, so the eleven states describe the usual path rather than police it. What is validated is coherence — a sent application gets a date, a closed one loses its follow-up.
- **2026-09-01 — The language of an ad decides the documents, and English is a real answer.** German when the text contains German, English when there is text and no German, and the profile's `locale_primary` only when there is nothing to go on. Falling back to the profile for an ad that simply has no German in it would send a German letter to an English posting, which is the failure this whole heuristic exists to prevent. Measured: 0 of 1289 descriptions lack a German function word.
- **2026-09-01 — Without a model, offers keep their reasons but not a total.** The obvious alternatives are both worse: no reasons at all throws away everything the profile and the offer's own fields already decided, and a total computed from five of the nine weights is not comparable to one from all nine — the same offer would score differently depending on whether a key happened to be configured that morning. So the reasons are written and the number is withheld, which is also what the frontend's unscored band was already built for.
- **2026-09-01 — `digest.schedule` is deleted; the digest is written at the end of an ingest run.** A cron in the configuration would need a scheduler that re-registers on every hot reload, and without one it is a key nothing reads — the third such key removed today. Whatever schedules the run schedules the digest.
- **2026-09-01 — `enrichment.extract.strategy` is `patterns`, and `readability` is gone.** Marcello's call. Nothing implemented `readability`, and a portal-agnostic full-text extractor would still leave rate, duration and workload unstructured — so the fields would have needed patterns anyway, and the dependency would have bought one mechanism on top of another. The rules now live in YAML exactly as `sources.yaml` does, for the same reason: every portal renders an ad differently, so a new one has to be a block and not a release. Readability stays a reasonable thing to add the day a real portal shows the patterns are not enough.
- **2026-09-01 — The page cache is a Postgres table, and it caches failures too.** The TTL is a week and the container has no volume for a scratch directory, so a file cache would not survive a restart and the rate limit would become a promise nobody keeps. Failures are cached because a 403 or a path disallowed by robots.txt is a fact about the page; a timeout is not cached, because it is a fact about the moment.
- **2026-09-01 — `onsite_cities` replaces `onsite_max_km`.** Marcello's call. Nothing computed the kilometres: an offer states its location as free text — "Remote und Nürnberg", "DE 7XXXX" — so a radius needs a dataset, a parser and a network call this stage must not need. The reference had always approximated the radius with a city list, and that list is what produced every measured number. A key nothing reads is decoration that outlives the intent it was written for, so it is gone and the intent survives as a comment. An empty list is logged at load, because a filter passing only remote offers looks exactly like a quiet market.
- **2026-09-01 — `role.rejected_title_keywords` is a new key rather than a second use of `anti_skills`.** That list is documented as a scoring penalty worth -30. Reading it as a knockout as well would mean anyone tuning the score silently changes what reaches the shortlist at all, and the two lists differ anyway: the knockout rejects roles — Scrum Master, Product Owner, Projektleiter, Tester, Support — not only stacks.
- **2026-09-01 — The whole `skill-profile.yaml` is bound, not just checked for existence.** The filter has to answer "does this offer name a skill I actually have", which the rules cannot say: they describe the criteria, not the person. Binding the file rather than the two fields the filter reads means scoring and the cover letter find it already there, and a typo in the profile fails at startup like every other configuration error.
- **2026-09-01 — The deduplication fingerprint is the normalized title and nothing else, and the obvious improvement was measured and rejected.** The configured field list names `city`, `start_date`, `duration_months` and `top_skills`; all four arrive from enrichment, which runs *after* this stage, so at F5 only the title exists. The one field that does exist is the stated location, and adding it collapses 111 offers instead of 159 — the 48 it gives up are overwhelmingly correct merges lost to the same ad writing "Nürnberg" in one portal and "Remote und Nürnberg" in the next, in 46 of 109 clusters. A location has to be parsed before it can be compared, and parsing it is enrichment's job. The cost is accepted and recorded in ISC-40 rather than hidden: two different projects sharing a title do merge.
- **2026-09-01 — Only `exact_fingerprint` is implemented; the two embedding strategies are logged and skipped.** They need a model, and the tool has to work without one. Failing at load instead would break the shipped defaults, which list them; running silently would leave the operator believing a similarity pass happened. A `merge_policy` other than `keep_first_seen_as_primary` is fatal at load, because that one *would* be read, ignored, and do the first-seen thing anyway.
- **2026-09-01 — Ochre means exactly one thing: this survived the filter.** The palette is two colours and a mute. Petrol carries structure and interaction, everything discarded is muted, and the accent is spent on the one fact the morning is about. It cost a rule when the dark primary became the logo's cyan at L 83 %: the review band moved from primary to secondary, because the middle band must never outshine "cleared the threshold".
- **2026-09-01 — The header carries `data-theme="lg-dark"` in both themes.** Marcello's call to place the real logo in the header. Its cyan is 1.6:1 on white and cannot be a light-theme colour at any size, so the lockup keeps a navy ground everywhere and looks identical in both modes. daisyUI's themes are attribute-scoped, so nesting the attribute re-declares every token for that subtree and the buttons, the muted version string and the two-tone wordmark follow with no override.
- **2026-09-01 — A component class name is checked against daisyUI's before it is used.** `status` is a 0.5 rem dot there and `label` is a form component; ours of the same names were laid out as those, silently. A utility framework's component classes share the global namespace, so the namespace is not ours to assume.
- **2026-09-01 — Five of six screens run on fixtures, and say so in every file.** The alternative was to build only the dashboard, which is the only screen with an endpoint, and learn nothing about the shape of the other five. The fixtures reproduce the measured coverage from `docs/SAMPLE-ANALYSIS.md` rather than convenient data, each feature reads through an `Api` seam so the real endpoint is one file, and ISC-69 keeps the marker on every fixture file.
- **2026-09-01 — Icons render from `lucide`'s node arrays behind one component, not from `lucide-angular`.** That package pins `@angular/core` to `13.x - 21.x` and excludes this repository's 22. The wrapper means the decision is reversible in two files and no template.
- **2026-09-01 — The override directory is `config/`, without the `local/` nesting.** Once the shipped defaults moved onto the classpath, `config/` held nothing but `local/`, and a directory whose only child is its own purpose is a directory too many.
- **2026-09-01 — The digest has no transport at all.** Marcello's call: both outputs are templates rendered to a file, text or HTML, and the application never sends. The `channel`/`transport`/`recipients` block is gone rather than made vendor-neutral — the honest fix for a schema modelling something the tool must not do is to delete the schema, not to generalize it. `format` and `output_dir` replace it.
- **2026-09-01 — No committed file picks a vendor, not even as a default.** The digest transport and the LLM provider both did. a named mail service as the digest transport, and a named model vendor as the LLM provider default, came out of the very first commit (`ca3eb76`, the concept and its example configs, written before any code existed) and were carried forward unexamined. A *kind* the code dispatches on is legitimate (`smtp | webhook`, `anthropic | ollama | openai-compatible`); a *default naming one of them* is the wiring-in the invariant exists to prevent, and it survives precisely because it looks like a helpful convenience. Model names likewise left `.env.example` as active values and stayed as comments. The digest keys went the same way in the entry above: deleted rather than generalized.
- **2026-09-01 — Configuration becomes two layers, and the shipped defaults move onto the classpath.** Marcello's call, after the name `application.yaml` outside Spring Boot proved confusing enough to derail a debugging session. `config/examples/` is gone: the four files now live in `backend/src/main/resources/leadgen/` and *are* the examples, so there is no second copy to drift. `config/local/` overrides them file by file, `.env` supplies every value, and both are gitignored. The tool's own file is `pipeline.yaml` behind `PipelineConfig`, because two files named `application.yaml` under `resources/` would have made the original complaint worse. The classpath directory is `/leadgen/` and not `/config/`, which Spring scans by default.
- **2026-09-01 — The configuration vocabulary keeps the implemented names and adopts four ideas from the hand-drafted `config/local/sources.yaml`.** Resolves the fog entry. Kept: `unwrap_query_param`, `ancestor`, `list`, and the field names `agency`/`published`/`tags`. Adopted: `expect_count_from_subject` (the announced count becomes a runtime check rather than only a test assertion), `prefer_part` (the part preference was hardcoded), per-field `format` (the source-level `date_format` stays as the fallback), and `inherit` (a second source names another's extraction instead of copying it — the example was already carrying two copies of one selector table). Not adopted: the named `transforms:` indirection, which buys a level of indirection for a single transform. The operator's file was migrated to match and reproduces the reference numbers.

## Learning

- **conjectured:** a screenshot shows what the browser shows, so a capture is enough to verify a widget.
  **refuted by:** every status select on the board reading "New" in the capture while the accessibility tree reported `combobox … value="SENT"` — and the score ring rendering as an opaque black disc on screens that had already been reviewed and approved.
  **learned:** the DOM-render capture serialises and re-renders, which drops state that lives only as a DOM property (a select's selection) and some component CSS on SVG children. It is evidence about layout and colour, not about widget state. Read the accessibility tree for state, and give an SVG its `fill` as an attribute so it cannot depend on a stylesheet arriving.
  **criterion now:** ISC-77 asserts the selection in a unit test, and ISC-78 asserts the write reached Postgres rather than that the screen looked right.

- **conjectured:** an optional flag on a PATCH body is naturally a `boolean` with a false default.
  **refuted by:** every request that omitted it coming back 400. Jackson will not map an absent value into a primitive, and the point of a PATCH is that it names one thing.
  **learned:** a primitive in a request record makes its field mandatory, silently and in a way the type does not say. And the tri-state was the honest model anyway: leave the follow-up alone, set it, remove it — three answers a `boolean` cannot hold.
  **criterion now:** ISC-73 asserts cancelling as its own case, not just overwriting.

- **conjectured:** a template rendering the row straight from the database will read the fields it names.
  **refuted by:** the archived ad coming out with "Rate: not stated" and "Published: unknown" for an offer that had both. The row's keys are snake_case; the template asked for `offer.fullText`, `offer.rateEur`, `offer.publishedOn`.
  **learned:** Freemarker resolves a missing key to nothing rather than failing, so the document renders, looks finished, and is wrong — in a file that goes to a client. The same class as every other finding today: the failure produced no error. The model is converted to camelCase once before rendering and the exception handler is set to rethrow, so the next missing value is loud.
  **criterion now:** ISC-51 asserts the archived ad contains the enriched full text, not merely that the file exists.

- **conjectured:** a scoring stage without a language model has nothing to say, so the honest answer is an unscored shortlist and no more.
  **refuted by:** listing what the weights actually ask for. Five of the nine — core-skill overlap, rate fit, seniority, project setup, industry — are decidable from the profile and the offer's own fields, with no model and no network. Only role fit and the three penalties need judgement.
  **learned:** "needs a model" is a property of a *factor*, not of a stage, and assuming otherwise had thrown away most of what the tool already knew. The remaining honest limit is the total rather than the reasons: five weights out of nine do not make a number comparable to nine out of nine.
  **criterion now:** ISC-50 asserts both halves — no total, and the deterministic reasons still written and readable.

- **conjectured:** a fetch result either succeeded or failed, and where it came from is a detail of the same shape.
  **refuted by:** the test asserting that a second run inside the TTL issues no request. It failed on the robots.txt case: the cached rejection was reported as a fresh failure, so `fromCache` stayed at zero and the report claimed a request had been made.
  **learned:** for anything that talks to the network, "did we ask" is a different question from "did it work", and a type that folds the two loses the one the rate limit and the politeness argument depend on. A cached 403 reporting itself as fresh does not fail anything visibly — it just makes the numbers in the report wrong.
  **criterion now:** ISC-47 asserts the request count, not only the outcome, and `FetchResult.cachedFailure` exists so the two cannot be conflated again.

- **conjectured:** the reference implementation is the measurement, so reproducing it exactly is the whole job — ISC-41 said 16.5 % ± 0 and called any deviation a bug.
  **refuted by:** reading it closely enough to reimplement it. Three defects, all silent, all in how text was compared. Its normaliser decomposed "Köln" and deleted the combining diaeresis in place, leaving `ko ln`, which matched no city in any list — **35 offers in Köln and 19 in Düsseldorf, the two cities nearest the home base, were discarded as out of reach.** Keywords were substrings, so `ch` for Switzerland rejected 127 German offers by matching Aachen and Bochum, `essen` accepted six offers from Hessen 200 km away, and `ANÜ` matched Planung and Manufacturing. And patterns were compared unfolded against folded text, so `.net` and `c#` matched nothing at all.
  **learned:** a reference implementation is a measurement of *something*, and reproducing it faithfully can mean reproducing a bug. The way to tell the difference is to reimplement it from the intent and then explain every disagreement — which is exactly the work that surfaced all three. Also: text comparison has three independent places to be wrong, and folding, boundaries and pattern normalisation each fail without an error.
  **criterion now:** ISC-41 targets 239 and 18.5 %, and `docs/samples/simulate_filter.py` was repaired in the same commit so that "matching the reference" stays a real constraint rather than a frozen number.

- **conjectured:** a title-only fingerprint is the weak version, and adding the stated location makes deduplication stricter and therefore better.
  **refuted by:** measuring both over the corpus before writing the code — title alone collapses 159, title plus location 111, and reading the 48 lost merges showed almost all of them were right.
  **learned:** a field that is *present* is not the same as a field that is *comparable*. The same ad writes its location as "Nürnberg", "Remote und Nürnberg" and "Remote & Nürnberg" across portals, so adding it does not tighten the key, it fragments it. Both numbers had to exist before the decision could be made, and the cheap probe that produced them took less time than the wrong implementation would have.
  **criterion now:** ISC-40 states what actually holds — exact normalized-title matching — instead of the stricter thing the fingerprint cannot deliver yet.

- **conjectured:** a class name assembled at runtime reaches the stylesheet the same way a literal one does.
  **refuted by:** grepping the built stylesheet for the badge tones — seven of eight were absent, and the badges had simply rendered with no colour while nothing reported a problem.
  **learned:** Tailwind scans source *text*. `'badge-' + tone()` exists only in the DOM at runtime and never in the source, so the class is never generated. Any variant a component can select must be spelled out somewhere a scanner can read it.
  **criterion now:** the tones live in a literal lookup map; ISC-63 checks the built stylesheet rather than the source.

- **conjectured:** declaring a transition on a property makes the element animate into view.
  **refuted by:** the funnel rail's reveal, which was in the stylesheet and did not happen.
  **learned:** a transition fires on a *change*. A width rendered correctly the first time never changes, so an entry animation needs two states and a painted frame between them — `afterNextRender` plus one `requestAnimationFrame`, not a longer duration.
  **criterion now:** the spec asserts the start state (every bar at 100 %) rather than the end state, because the end state is what a broken implementation also produces.

- **conjectured:** a routed input keeps its declared default when the query parameter is absent.
  **refuted by:** the shortlist rendering half a page — title empty, most controls gone, console silent — the first time it was opened without a filter.
  **learned:** `withComponentInputBinding()` writes `undefined` over the default, and the resulting `undefined.trim()` throws *inside* the template, which stops the update pass partway and leaves whatever had already rendered. Every routed input needs a transform.
  **criterion now:** ISC-70, which opens the page with no parameters at all — the case that had never been tested because it is the default one.

- **conjectured:** reading `.env` in a `bootRun` hook covers the local run, because the local run is `bootRun`.
  **refuted by:** starting the very same configuration from an IDE — the values were in the file and the service reported them missing, with an error naming the right setting and no reason.
  **learned:** a mechanism that lives in the build privileges one start path and silently excludes every other. The application has at least three (Gradle, IDE, jar) and they must not differ.
  **criterion now:** ISC-59. `PlaceholderResolver` reads the file itself; the build hook is deleted rather than duplicated.

- **conjectured:** `env_file:` in `docker-compose.yml` is how Compose learns the values in `.env`.
  **refuted by:** a counter-check — changing `POSTGRES_PORT` in the file left the published port untouched. `COMPOSE_ENV_FILES` written inside `.env` did not help either.
  **learned:** two unrelated mechanisms share one word. `env_file:` injects variables INTO a container; the `${...}` in the compose file are substituted from `.env` alone, and redirecting that needs a real environment variable or `--env-file`. The failure is silent: the defaults apply and the stack listens where the application is not looking.
  **criterion now:** ISC-61 is verified by changing the value and reading `docker compose config` back, never by reading the file and assuming.

- **conjectured:** `password authentication failed for user "leadgen"` is a credentials problem.
  **refuted by:** looking at what actually held the port — another project's Postgres on 5432, while ours was not running at all.
  **learned:** the message names the user and omits host, port and database, which are the only three facts that would have identified it. A process that reaches something configurable has to say what it reached.
  **criterion now:** ISC-60, and the host port default moved off 5432.

- **conjectured:** opening an IMAP folder read-only is enough to leave the owner's mail unflagged.
  **refuted by:** `ImapSourceConnectorTest.neverFlagsAMessageAsSeen` against a real IMAP server — the flag was set anyway.
  **learned:** fetching a body issues `FETCH BODY[]`, and the server sets `\Seen` regardless of how the folder was opened; only `BODY.PEEK[]` does not, and JavaMail uses it solely when `mail.imap.peek` is on.
  **criterion now:** ISC-30 verifies the flag against a real server rather than asserting the open mode.

- **conjectured:** a green unit suite plus a successful production build means the deployed page works.
  **refuted by:** the first browser check after the container build — a blank page with no console error.
  **learned:** `index.html` still hosted `<app-root>` while the component selector was `lg-root`. `TestBed` creates the component itself, so every test stayed green; the mismatch is invisible to the suite by construction.
  **criterion now:** ISC-9 is a real-browser probe, not a unit assertion.

- **conjectured:** search terms arrive wrapped in `<mark>` and deduplication will trip over them, as the repository documentation warned.
  **refuted by:** counting `<mark` across all 14 sample mails — zero occurrences.
  **learned:** the trap is an expectation carried over from another source, not a measurement of this one. jsoup's `text()` strips it regardless, so the guard costs nothing and proves nothing about this corpus.
  **criterion now:** ISC-24 covers the mechanics against a synthetic fixture; the documentation says which of the two it is.

- **conjectured:** accepting a configuration path that carries its own directory is a harmless kindness, since it only ever resolves to the file the author meant.
  **refuted by:** a probe run against a copied configuration directory, which loaded the *original* `sources.yaml` from the repository instead of the copy beside it, and looked entirely normal doing it.
  **learned:** the fallback can resolve outside the directory the process was pointed at, so two configurations silently become one. Forgiving resolution is fine; forgiving it *quietly* is not.
  **criterion now:** ISC-57. The forgiving resolution is gone entirely rather than made loud — a path in `pipeline.yaml` names a file, and the two layers decide where it comes from. A warning would have documented the hazard; removing the mechanism removes it.

- **conjectured:** a default of `../config/local` is correct because the backend runs from `backend/`.
  **refuted by:** starting the application from the IDE, which resolved it one directory above the repository.
  **learned:** the working directory is not one thing — Gradle's `bootRun` uses `backend/`, an IDE run configuration the repository root, a jar wherever it sits. A default correct in one is wrong in the others, and the symptom is a missing file at a path nobody recognises.
  **criterion now:** ISC-12 asserts resolution from both working directories.

## Verification

- ISC-1 … ISC-4 — `817090f`, plus `rg` sweep over the tracked tree
- ISC-5 — `./gradlew check`, 49 tests
- ISC-8, ISC-9 — `docker compose up`, `curl 127.0.0.1:4200/api/status`, Interceptor screenshot
- ISC-10 — `LeadGenerationApplicationTests.flywayCreatedTheBaselineSchema`
- ISC-11, ISC-12 — `3081337`, `ConfigProperties.configDirectory`
- ISC-13 — `frontend/eslint.config.mjs`, `bun run lint`
- ISC-14 … ISC-21 — `9759092`, `ConfigLoaderTest` (9), `ConfigWatcherTest` (5), `PlaceholderResolverTest` (6)
- ISC-22 … ISC-27 — `817090f`, `SampleCorpusAcceptanceTest` (6), `ExtractionTest` (9)
- ISC-26 — `IngestServiceTest.readingTheSameMailAgainAddsNothing`
- ISC-29 … ISC-34 — `ImapSourceConnectorTest` (8), GreenMail
- ISC-53, ISC-54 — `POST /api/ingest` against the operator's own migrated rules: 14 documents, 1289 offers, announced equals extracted in all 14
- ISC-59, ISC-60, ISC-61 — `46e0d9c`; `java -jar` from the repository root logs `Reading …/.env` and `Database: jdbc:postgresql://localhost:55432/leadgen as leadgen`; port counter-check 15432 ↔ 55432
- ISC-84, ISC-85 — `ManualUploadServiceTest` (8) against Postgres 17 and `ManualSourceControllerTest` (5) against MockMvc: the upload lands where no source globs it, the extraction is shown before anything enters, the duplicate is named before the confirm, the correction is written into the file and the file is moved, a rejection leaves nothing behind, and a wrong extension, an oversized file and three traversing names are all refused. `ReviewCard` covers the correction path in the browser. Verified live end to end: a file uploaded through the drop zone, `portal` corrected to a value the document never carried, confirmed, and the next run wrote the offer to Postgres with that value
- ISC-79 … ISC-82 — `MarkdownExtractionTest`, 6 tests through the shipped `manual-inbox` block and the real file connector: every frontmatter field plus the body, a proxy link unwrapped, tags as a list and as a typed line, a content-hash id stable across two reads, no offer from a file without frontmatter, and a `---` in the body read as a thematic break. `IngestServiceTest` adds the wired pass against Postgres: a file dropped in the inbox by hand, with no upload involved, reaches the offer table
- ISC-83 — `git status` after a full suite run: no directory created outside the configuration directory
- ISC-75 … ISC-77 — `applications.store.spec.ts` (4) and `status-picker.spec.ts` (3) against `HttpTestingController`: lanes from the server, the row replaced by the answer, the failed PATCH leaving the board untouched, and the picker showing a state that is not the first option
- ISC-78 — live browser against a running API: picking SENT on a board card moved it from Prepared to Out with the server's date, and clearing the follow-up on the detail wrote `follow_up_on = NULL`, both confirmed in Postgres
- ISC-71 … ISC-74 — `ApplicationServiceTest` (12) against Postgres 17 and `ApplicationControllerTest` (5) against MockMvc: opening twice around a status change, a backwards transition accepted, the event log after three moves, a follow-up due on its day and dropped on closing and cancelled on request, and the endpoint answering 400 for an unknown status and 404 for an unknown id
- ISC-51 — `PackagingServiceTest`, 8 tests against Postgres 17: a folder with cover letter, archived ad, `meta.json` and the CV; German and English letters chosen by the ad; the enriched full text in the archive; score and reasons in `meta.json`; every portal of a duplicate cluster named once; only offers above the threshold packaged; no package built twice; a missing CV recorded rather than fatal
- ISC-52 — `NothingIsSentTest`, 3 tests reading the repository for send paths and transport keys across backend, shipped configuration and frontend
- ISC-48, ISC-50 — `ScoringWithAModelTest` (5) and `ScoringWithoutAModelTest` (5) against WireMock and Postgres 17: a reason per factor with the total equal to their sum, an invented factor dropped, a model awarding itself 900 clamped to 15, an unreachable endpoint and a non-JSON answer both leaving the offer scored on rules alone, and the no-key path producing a null score with its deterministic reasons intact
- ISC-49 — `DigestServiceTest` (4): a file naming both bands with the reasons beside every number, an unscored heading of its own, and the artefact saying in words that nothing has been sent
- ISC-45 … ISC-47 — `EnrichmentServiceTest`, 6 tests against WireMock and Postgres 17: seven fields off a stubbed ad, a 403 leaving the offer PASSED with a note, a second run inside the TTL issuing zero requests, a robots-disallowed path never fetched and remembered, and only filtered-through offers considered; `RobotsPolicyTest`, 6 tests on the parsing conventions
- ISC-41 … ISC-44 — `HardFilterCorpusTest` over all 14 mails: 1289 offers, 239 passed (18.5 %), seven stage counts equal to the reference (717 / 171 / 115 / 25 / 12 / 8 / 2), removals plus survivors equal to the total; `HardFilterTest`, 14 tests against a fictional rule set, covering the fold, word boundaries, pattern folding, the unstated remote share and the rate never being read
- filter defects — measured before and after: umlaut fold worth 54 offers (35 Köln, 19 Düsseldorf); `ch` as a substring worth 127 false abroad rejections; `essen` accepting 6 Hessen offers; `ANÜ` as a substring worth 23 false contract rejections; core-skill aliases worth 12 offers
- ISC-37 … ISC-40 — `DeduplicationServiceTest`, 6 tests against Postgres 17 in Testcontainers; the corpus half of ISC-38 in `SampleCorpusAcceptanceTest.findsTheMeasuredNumberOfDuplicateTitles`, run against all 14 mails, 159 of 1289
- fingerprint composition — measured over the corpus before implementing: title 159 collapsed (12.3 %), title+location 111 (8.6 %), title+location+agency 75 (5.8 %); 46 of 109 title clusters span several location spellings of the same place
- ISC-62 … ISC-70 — `2086523`; `./gradlew check` green with 23 frontend specs; six routes probed at 320/768/1440 through a lifecycle-live browser, page scroll width equal to the viewport in all eighteen; 34 tabbable elements, 0 without an accessible name; the inline theme script at head position 6 against both stylesheet links at 7 and 8
- font axes — Bricolage Grotesque measured in the browser: wght 200 vs 800 renders 460 vs 497 px, opsz 12 vs 96 renders 505 vs 452 px, so `opsz.css` carries both axes and `standard.css` is not needed
- ISC-55, ISC-56, ISC-57 — boot with `LEADGEN_CONFIG_DIR=/nonexistent` (four files from `classpath:/leadgen/`) and with `config/local` (four files overridden), both logged per file

## Remaining Work

- [ ] **Next: the last four fixture screens.** Shortlist, offer detail, sources and rules all wait on a read endpoint over the offers, which is a smaller job than any pipeline stage was and the last thing keeping invented data on screen. `rg 'FIXTURE — replace'` finds every one.
- [ ] CI. The tooling baseline is in place and no pipeline runs it, so every check in this artifact is one somebody remembered to run.
- [ ] Manual entry: uploading a Markdown file as a source, reviewed in `inbox/pending/` before it enters the pipeline. The second write endpoint, and the first that puts a file on disk.
- [ ] `security.auth` is still `none`, and there is now a write endpoint behind it. Either the service binds to localhost and lives behind something that authenticates, or it grows basic auth or OIDC — the block already exists in `pipeline.yaml`.
- [ ] An eval for the judge. ISC-48 proves the weights bound the model; it says nothing about whether the model judges *well*. That is a held-out set and a rubric, and it belongs after a real model has scored a real morning.
- [ ] Run `bun ~/.claude/LIFEOS/TOOLS/IsaFrontier.ts frontier ISA.md` to see the takeable set before picking anything else up.

- [ ] The five fixture-driven screens stay fixtures until steps 5 to 9 land. `rg 'FIXTURE — replace'` finds every one; each feature already reads through an `Api` seam, so each is one file.
- [ ] The frontend's write side, none of which exists: recording an application's status after the mail was sent by hand, editing weights and thresholds, and uploading a Markdown file as a manual source. The first of them is the first write endpoint in the application and lands with `security.auth` still `none`.
- [ ] Decide the Java package name; `de.codeministry.leadgen` was chosen before the repository name and organisation were.
- [ ] `docs/CONCEPT.md` still names the operator's home town in the hard-filter section. Not a source, so it was left in place, but it is a personal datum in a repository that is going public.
- [ ] The Helm chart in `charts/` is named in the concept as phase two and does not exist yet.
- [ ] `docs/CONCEPT.md` carries its own order-of-work list, which now duplicates this ISA and `CLAUDE.md`. Reconcile to one.
