---
phase: climbing
progress: 37/58
task: "Acquisition tool: collect, filter, enrich and package project offers"
slug: lead-generation
started: 2026-09-01T11:30:00Z
updated: 2026-09-01T14:10:00Z
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
- **default vs override** — a configuration file exists twice: the committed default on the classpath under `/leadgen/`, and optionally a file of the same name in `config/local/` that replaces it whole. _Avoid_: "the config file" without saying which layer; the startup log names one per file.
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
- The repository is going public. No portal name, no mail provider, no newsletter, no model name and no personal datum in code or in tracked configuration. The configuration that ships names every value as a `${PLACEHOLDER}`; the values live in `.env.local` and anything individual beyond them in `config/local/`, both gitignored.
- Configuration comes in two layers, the same as Spring's own: working defaults on the classpath in the jar, an external directory overriding them file by file. The tool runs on a fresh clone with nothing configured.
- The subscriber's mail address must not leave the machine. Newsletter links are tracking proxies carrying it; anything derived from an unwrapped link would carry it into the database and into every exported package.
- Progress through a mailbox is tracked by `UIDVALIDITY`/`UID` and never by seen/unseen flags.
- Everything in the repository is English — code, comments, documentation, tests, log output. The offers are German and the cover letters are German; that is *content*, it lives in `config/local/`, and it is selected by the language of the ad.
- The DA never commits. Work is left uncommitted and the commit is offered.

## Goal

A single operator drops mailbox credentials and a profile into `config/local/`, and every morning receives a scored shortlist of roughly fifteen offers drawn from over a hundred, deduplicated across portals, enriched with the rate and full text the newsletter omitted, with a ready-to-send application package assembled for everything above the shortlist threshold — and sends nothing without deciding to.

## Not yet specified

- fog: how a merged duplicate cluster is named and addressed once it is no longer a single offer — F5 and F8 both need the distinction, and the Language section already records that the word `offer` is carrying two meanings.
- fog: the frontend's shape beyond the shortlist. Pipeline kanban, sources and profile editing are named in the concept but nothing about them is precise enough to falsify yet.

## Test Strategy

| isc | type | check | threshold | tool | anchors_to |
|---|---|---|---|---|---|
| ISC-1 | bash | grep the tracked tree for the anonymized terms and the address | 0 hits | rg | `.gitignore`, `docs/SAMPLE-ANALYSIS.md` |
| ISC-2 | bash | `git add -An .` and match against the ignore list | 0 hits | git | `.gitignore` |
| ISC-3 | bun-test | assert every extracted URL over the corpus | 0 of 1289 | JUnit | `SampleCorpusAcceptanceTest` |
| ISC-4 | bash | grep Java sources for selectors and emoji prefixes | 0 hits | rg | `backend/src/main/java` |
| ISC-58 | bash | grep the tracked tree for known product and vendor names | 0 hits | rg | `resources/leadgen/`, `.env.local.example` |
| ISC-5 | bash | `./gradlew check` | exit 0 | Gradle | root build |
| ISC-6 | manual | licence file present and referenced | | review | `LICENSE` |
| ISC-7 | bash | pipeline run on a pushed branch | red blocks merge | CI | not yet authored |
| ISC-8 | curl | `docker compose up` then probe api, web root and a client route | 3× HTTP 200 | curl | `docker-compose.yml` |
| ISC-9 | screenshot | open the page in a real browser and read the API-sourced value | value rendered | Interceptor | `frontend/src/app/app.html` |
| ISC-10 | bun-test | query `information_schema.tables` after startup | 4 tables | Testcontainers | `LeadGenerationApplicationTests` |
| ISC-11 | bun-test | test context boots without a `.env` | green | JUnit | `backend/build.gradle.kts` |
| ISC-12 | bun-test | resolve from repository root and from `backend/` | same directory | JUnit | `ConfigProperties` |
| ISC-13 | bash | `bun run lint` with a deliberate cross-layer import | exit 1 | ESLint | `frontend/eslint.config.mjs` |
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
| ISC-37 | bun-test | two listings of one project from different portals | one entry, two sources | JUnit | not yet authored |
| ISC-38 | bun-test | deduplicate the corpus | 159 collapsed | JUnit | `docs/SAMPLE-ANALYSIS.md` |
| ISC-39 | bun-test | merge order and attached sources | first-seen primary | JUnit | not yet authored |
| ISC-40 | bun-test | two distinct projects with near-identical titles | not merged | JUnit | not yet authored |
| ISC-41 | bun-test | run the filter over the corpus | 16.5 % ± 0 | JUnit | `docs/samples/simulate_filter.py` |
| ISC-42 | bun-test | per-stage removal counts sum to the total | exact | JUnit | not yet authored |
| ISC-43 | bun-test | offer with no stated remote share | survives, flagged | JUnit | not yet authored |
| ISC-44 | bun-test | offer below the rate floor before enrichment | survives | JUnit | not yet authored |
| ISC-45 | bun-test | fetch a stubbed ad page | 4 fields extracted | WireMock | not yet authored |
| ISC-46 | bun-test | fetch returning 403 | offer kept, marked incomplete | WireMock | not yet authored |
| ISC-47 | bun-test | disallowed path, rate limit, warm cache | 0 requests | WireMock | not yet authored |
| ISC-48 | eval | score a held-out set of offers against a rubric | reasons cite real fields | EvalRunner | not yet authored |
| ISC-49 | bun-test | render the digest from a seeded database | file written, both bands present | JUnit | not yet authored |
| ISC-50 | bun-test | run the pipeline with no LLM key | completes, offers unscored | JUnit | not yet authored |
| ISC-51 | bun-test | package an offer above the threshold | 4 files, ad's language | JUnit | not yet authored |
| ISC-52 | bash | grep the tree for a send path and for transport/recipient config keys | 0 hits | rg | whole repository |

## Features

### F0 · Cross-cutting

**Why:** The invariants that hold no matter which pipeline stage is being built, and each of which fails silently when broken — a leaked address is invisible until the repository is public, and a wired-in portal name is invisible until someone else tries to use the tool.

- [x] ISC-1: No tracked file contains the subscriber's mail address, a portal name, a mail provider or a newsletter sender; the anonymized forms are `<newsletter-sender>`, `<aggregator-host>` and `portal-a`…`portal-f`.
- [x] ISC-2: `config/local/`, `.env.local`, `docs/samples/emails/` and everything derived from them are gitignored, and a dry-run `git add` of the whole tree lists none of them.
- [x] ISC-3: Anti: no URL persisted by any pipeline stage contains `@`, `email=` or `%40`.
- [x] ISC-4: Anti: no CSS selector, sender address, folder name or portal name appears in Java source.
- [x] ISC-58: Anti: no committed file names a concrete service, product, vendor or model as a configuration value, **including as a default** — transports, providers and portals are kinds, and which one is used lives in `.env.local`.
- [x] ISC-5: `./gradlew check` runs both modules — backend tests and frontend lint plus tests — in one invocation and is green.
- [ ] ISC-6: A licence is chosen, added as `LICENSE`, and referenced from `README.md`.
- [ ] ISC-7: CI runs `./gradlew check` on every push and blocks a merge on red.

### F1 · Monorepo and operation

**Why:** Everything downstream is written against a stack that has to be provably real first; a skeleton that compiles but has never served a page is a skeleton that hides its own gaps.

- [x] ISC-8: `docker compose up --build` brings postgres, api and web up, with the API answering `GET /api/status` and nginx serving the SPA with a client-route fallback.
- [x] ISC-9: The frontend reaches the backend through the nginx proxy and renders a value that came from it.
- [x] ISC-10: Flyway executes its migrations against a real Postgres at startup; a test asserts the resulting tables exist rather than that the context merely loaded.
- [x] ISC-11: The backend reads the untracked `.env` on `bootRun` and never through `spring.config.import`, so no test context depends on a file absent from the repository.
- [x] ISC-12: `leadgen.config-dir` resolves correctly whether the process is started by Gradle (working directory `backend/`), by an IDE (repository root) or from a jar.
- [x] ISC-13: The layering `shared → core → layout → features` is enforced by lint and fails the build on a cross-layer import.

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

- [ ] ISC-37: Exact-fingerprint duplicates are merged, and the merged entry names every source it came from.
- [ ] ISC-38: Deduplicating the sample corpus collapses 159 offers, matching the reference measurement.
- [ ] ISC-39: The merge keeps the first-seen offer as primary and attaches the others, so no source is lost.
- [ ] ISC-40: Anti: two genuinely different projects with similar titles are not merged.

### F6 · Hard filter

**Why:** This is the stage that turns a hundred offers into fifteen, and it is the one whose failure is indistinguishable from a quiet market — which is why it has a measured target rather than a plausible one.

- [ ] ISC-41: The hard filter passes 16.5 % of the 1289 sample offers, matching `docs/samples/simulate_filter.py`; a deviation is a bug and not a matter of taste. (after: ISC-37)
- [ ] ISC-42: Each of the five filter stages reports how many it removed, so a change in the total is attributable.
- [ ] ISC-43: An offer whose remote share is unknown survives and is flagged, rather than being discarded.
- [ ] ISC-44: Anti: `min_hourly_eur` has no effect at this stage.

### F7 · Enrichment

**Why:** The newsletter states no rate, no duration and no workload in any of 1289 offers; without fetching the original ad the scoring stage would be judging a generated two-line summary.

- [ ] ISC-45: For a filtered offer, the original ad is fetched and rate, duration, workload and full text are extracted from it. (after: ISC-41)
- [ ] ISC-46: A failed or forbidden fetch leaves the offer in the pipeline marked incomplete, and never discards it.
- [ ] ISC-47: Fetching respects `robots.txt`, the configured rate limit and the cache TTL, and a second run within the TTL issues no request.

### F8 · Scoring and digest

**Why:** The reason an offer scored what it scored is the part the operator actually reads; a number without a reason gets ignored within a week.

- [ ] ISC-48: Each surviving offer receives a score with the weights from `matching-rules.yaml` and a stated reason per contributing factor. (after: ISC-45)
- [ ] ISC-49: The daily digest is rendered to a file as text or HTML, lists the shortlist and the review band, and is produced without a frontend.
- [ ] ISC-50: Anti: with no LLM key configured, the pipeline still runs and produces an unscored shortlist rather than failing.

### F9 · Application package

**Why:** The hour saved on sorting is given back if assembling the documents still takes twenty minutes per application.

- [ ] ISC-51: An offer above the shortlist threshold produces a folder with a cover letter in the language of the ad, the matching fixed CV, the archived original and a `meta.json`.
- [ ] ISC-52: Anti: nothing is sent; the tool has no send path at all, and the configuration models no transport, recipient or channel either.

## Decisions

- **2026-09-01 — Portal and source names anonymized in the documentation.** The analysis docs named the aggregator, its sender and host, the mail provider and six portals in clear text. The repository is going public; they are now `<newsletter-sender>`, `<aggregator-host>` and `portal-a`…`portal-f`, with the real names in the gitignored `config/local/sources.yaml`. Every measured figure is unchanged.
- **2026-09-01 — The frontend is bracketed by plain Gradle `Exec` tasks calling bun, not by the Node Gradle plugin.** The plugin does not speak bun, and bun is the package manager everywhere here. `package.json` stays the single list of frontend commands.
- **2026-09-01 — JDBC replaces JPA.** The pipeline writes in batches and upserts with `ON CONFLICT`, which is one plain SQL statement against a schema Flyway owns. An ORM would have added a mapping layer over Postgres arrays for no gain. Flyway is now the only thing that touches the schema.
- **2026-09-01 — `rules.hot_reload` switches all three configuration files, not only the rules.** They are read and swapped as one snapshot; reloading one without the others would hand the pipeline a picture that never existed on disk.
- **2026-09-01 — Change detection polls timestamps instead of using `WatchService`.** For three files the efficiency argument is worth nothing, the watch service is native only on Linux, and on macOS the JDK falls back to polling with a ten-second default latency — the same mechanism with platform-dependent timing nobody can reason about.
- **2026-09-01 — Paths in `application.yaml` are file names resolved against the configuration directory.** They carried `config/local/` themselves, which breaks the moment the directory moves; inside the container it is `/config`. A path with the directory baked in is still accepted, resolved upwards from the working directory, because breaking an existing configuration over a style is not worth it.
- **2026-09-01 — Skills (`span.skill-pill`) are not modelled.** Dead end: the reference implementation extracts them, and the element does not occur in the corpus at all. Coverage 0 %.
- **2026-09-01 — Lombok for boilerplate, records for data.** `@Slf4j` and `@RequiredArgsConstructor` where the constructor is nothing but assignments; not where it does work, and not where parameters carry `@Value`. API types stay records, each in its own file. The configuration model keeps its nesting, which mirrors the YAML it describes.
- **2026-09-01 — The cursor is committed after the write, not after the read.** `SourceConnector.commit` exists for that alone.
- **2026-09-01 — The digest has no transport at all.** Marcello's call: both outputs are templates rendered to a file, text or HTML, and the application never sends. The `channel`/`transport`/`recipients` block is gone rather than made vendor-neutral — the honest fix for a schema modelling something the tool must not do is to delete the schema, not to generalize it. `format` and `output_dir` replace it.
- **2026-09-01 — No committed file picks a vendor, not even as a default.** The digest transport and the LLM provider both did. a named mail service as the digest transport, and a named model vendor as the LLM provider default, came out of the very first commit (`ca3eb76`, the concept and its example configs, written before any code existed) and were carried forward unexamined. A *kind* the code dispatches on is legitimate (`smtp | webhook`, `anthropic | ollama | openai-compatible`); a *default naming one of them* is the wiring-in the invariant exists to prevent, and it survives precisely because it looks like a helpful convenience. Model names likewise left `.env.local.example` as active values and stayed as comments. The digest keys went the same way in the entry above: deleted rather than generalized.
- **2026-09-01 — Configuration becomes two layers, and the shipped defaults move onto the classpath.** Marcello's call, after the name `application.yaml` outside Spring Boot proved confusing enough to derail a debugging session. `config/examples/` is gone: the four files now live in `backend/src/main/resources/leadgen/` and *are* the examples, so there is no second copy to drift. `config/local/` overrides them file by file, `.env.local` supplies every value, and both are gitignored. The tool's own file is `pipeline.yaml` behind `PipelineConfig`, because two files named `application.yaml` under `resources/` would have made the original complaint worse. The classpath directory is `/leadgen/` and not `/config/`, which Spring scans by default.
- **2026-09-01 — The configuration vocabulary keeps the implemented names and adopts four ideas from the hand-drafted `config/local/sources.yaml`.** Resolves the fog entry. Kept: `unwrap_query_param`, `ancestor`, `list`, and the field names `agency`/`published`/`tags`. Adopted: `expect_count_from_subject` (the announced count becomes a runtime check rather than only a test assertion), `prefer_part` (the part preference was hardcoded), per-field `format` (the source-level `date_format` stays as the fallback), and `inherit` (a second source names another's extraction instead of copying it — the example was already carrying two copies of one selector table). Not adopted: the named `transforms:` indirection, which buys a level of indirection for a single transform. The operator's file was migrated to match and reproduces the reference numbers.

## Learning

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
- ISC-55, ISC-56, ISC-57 — boot with `LEADGEN_CONFIG_DIR=/nonexistent` (four files from `classpath:/leadgen/`) and with `config/local` (four files overridden), both logged per file

## Remaining Work

- [ ] Decide the Java package name; `de.codeministry.leadgen` was chosen before the repository name and organisation were.
- [ ] `docs/CONCEPT.md` still names the operator's home town in the hard-filter section. Not a source, so it was left in place, but it is a personal datum in a repository that is going public.
- [ ] The Helm chart in `charts/` is named in the concept as phase two and does not exist yet.
- [ ] `docs/CONCEPT.md` carries its own order-of-work list, which now duplicates this ISA and `CLAUDE.md`. Reconcile to one.
