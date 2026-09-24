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
the profile are German. That is **content**, it lives in `config/` and in i18n
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
- **A profile topic moves a score and never passes a knockout, and the scorer stores the match.** The
  topic filter reads `offer_score_reason.topic`; it never matches text itself.
- **Ollama is the provider, with no automatic fallback; any other only when set for that run.** — reasoning in `docs/decisions/pipeline-scoring.md`.
- **Pre-1.0 a breaking change is a PATCH; only how the artifact is built or run moves the MINOR.** — reasoning in `docs/decisions/native-image.md`.
- **Production maintenance never touches the codebase.** Bulk re-import, rescoring, a
  bulk-archive: these run against the live instance from the terminal or as direct SQL. A
  one-off admin task that leaves a commit behind has been done wrong, because the next
  release then carries a migration nobody asked for.
- **The database image is the pinned `pgvector/pgvector:0.8.6-pg18`, set in `docker-compose.yml` and `Databases.java` and nowhere else.** — reasoning in `docs/decisions/retrieval.md`.
- **Mount the database volume at `/var/lib/postgresql`, never `…/data`; a major bump moves tag, `PGDATA`, mount and data at once.** — reasoning in `docs/decisions/retrieval.md`.
- **The vector column is 2000 wide, a wider vector is truncated at the seam, and thresholds move only after measuring with `docs/samples/measure_embeddings.ts`.** — reasoning in `docs/decisions/retrieval.md`.
- **No CV tailoring.** Fixed PDFs in `config/documents/`, selected by the language
  of the ad and nothing else.
- **Nothing is ever sent.** Both outputs are rendered files: the digest as text or HTML,
  the application package as a folder. There is no transport, no recipient and no channel
  in the configuration either — modelling one would be an invitation to add the code.
- **Two configuration layers: shipped classpath defaults, overridden file by file from `leadgen.config-dir`.** — reasoning in `docs/decisions/configuration.md`.
- **The mail address never leaves the machine.** Newsletter links are proxied as
  `…/proxy?target=…&email=…`. Unwrap `target`, discard `email`. Raw `.eml` files and
  anything derived from them are gitignored — they carry the address in headers and
  unsubscribe links.
- **`min_hourly_eur` must not apply before the enrichment stage.** The newsletter carries
  a rate in 0.0 % of offers. Applied earlier, the rule filters either everything or nothing.
- **A package is built only when a person moves an application to `PACKAGED`, and no transition skips it.** — reasoning in `docs/decisions/manual-status.md`.
- **Archiving discards the package unless the application was ever sent, and a restore comes
  back at `NEW`.** Both halves keep "PACKAGED" and "there is a folder" the same fact.
- **Anything reached by a name computed at runtime needs a hint in `LeadGenRuntimeHints`.**
  The five YAML files and the three templates are; a missing hint is an empty result in a
  native image, not an error. `LeadGenRuntimeHintsTest` fails when a new one has none.
- **Never commit.** Do the work, leave it uncommitted, offer the commit — the maintainer
  reviews the diff and decides what lands.

## Monorepo

`backend/` (Spring Boot 4.1, Java 25, Gradle) · `frontend/` (Angular 22 zoneless +
`@ngrx/signals` + Tailwind 4/DaisyUI) · `config/` · `docs/`.
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

## The decision records

The paragraphs behind each pipeline stage — what was measured, what it cost, and why the
obvious alternative was not taken — live in `docs/decisions/`. They are not loaded with this
file; open the one you need. **A new decision gets one line of rule here and its reasoning
there**, which is what keeps this file readable.

| Topic                                                                       | File                                        |
|-----------------------------------------------------------------------------|---------------------------------------------|
| Connectors, the extraction table, the Markdown inbox and its review         | `docs/decisions/pipeline-ingest.md`         |
| What collapses a duplicate, the six filter stages, the archive axis         | `docs/decisions/pipeline-dedupe-filter.md`  |
| The fetch that leaves the machine, block labelling, start/duration/deadline | `docs/decisions/pipeline-enrich-content.md` |
| Rules before model, the weight table, the digest and the package folder     | `docs/decisions/pipeline-scoring.md`        |
| The working-set predicate, keyset paging, the ten sort keys, the filters    | `docs/decisions/read-side.md`               |
| The two configuration layers and the startup banner                         | `docs/decisions/configuration.md`           |
| The three split screens, the shell, the write path                          | `docs/decisions/frontend-split-views.md`    |
| Both themes, the signal's one meaning, the tiers, the sections, the catalogs | `docs/decisions/frontend-design-system.md`  |
| The eleven application states and their event log                           | `docs/decisions/manual-status.md`           |
| Vectors, the search that narrows, the pgvector image and its data mount     | `docs/decisions/retrieval.md`               |
| The sixteen steps this tool was built in, and what each had to prove        | `docs/decisions/order-of-work.md`           |
| The AOT cache, the native image, the hints, and pre-1.0 versioning          | `docs/decisions/native-image.md`            |

The conventions and the traps for each half sit beside the code, in `backend/CLAUDE.md` and
`frontend/CLAUDE.md`. A nested file is loaded when a file in that tree is read, never at
startup, so neither costs anything while you are working in the other one.

**Where a new rule goes.** The rule itself — one or two lines, the imperative — goes into the
file that is loaded when somebody could break it: this one if it is repo-wide, `backend/` or
`frontend/` if it belongs to one tree. Everything behind it — the measurement, what it cost,
why the obvious alternative was not taken — goes into the matching file in `docs/decisions/`.
Both halves are worth keeping; only one of them has to be in context at all times.

Measured, not taste: this file went from 20,641 to 166,477 characters in two weeks when
every decision landed here whole. `WorkingNotesStaySmallTest` holds the split (budget, paths
exist, every decisions doc reachable from the table above).

## What already exists

```
backend/src/main/resources/leadgen/    the committed defaults — neutral, all values as
  pipeline.yaml                        ${PLACEHOLDERS}. These ARE the examples; there is
  matching-rules.yaml                  no second copy to drift.
  sources.yaml
  skill-profile.yaml
  cover-letter.yaml
config/*.yaml                     the same five names, overriding file by file (gitignored)
.env.example
docs/samples/emails/*.eml         14 real newsletter mails (gitignored)
docs/samples/analyze_samples.py   extraction, field coverage, duplicates
docs/samples/simulate_filter.py   simulation of the hard filters
docs/decisions/*.md               the reasoning per stage, moved out of this file
docs/ADDING-A-SOURCE.md           the worked example, then every sources.yaml key
docs/WRITING-RULES.md             every matching-rules.yaml key and what reads it
docs/DATA-MODEL.md                the 13 tables, their keys, who writes each column
docs/BACKEND-FLOWS.md             the run as a sequence, the async tails, the write paths
backend/CLAUDE.md                 backend conventions and the traps of that tree
frontend/CLAUDE.md                frontend conventions and the traps of that tree

backend/…/content/                block splitting, the digest, the label cache, the
                                  classifier — decisions/pipeline-enrich-content.md
backend/…/fields/                 start, duration and deadline, read out of the advert —
                                  decisions/pipeline-enrich-content.md
backend/…/llm/                    ChatModels and Answers, shared by the judge and the
                                  classifier
frontend/src/styles.css           both DaisyUI themes, the fonts, the @theme block —
                                  the only file allowed to hold a colour literal
frontend/src/styles/tokens.css    semantic aliases, layout constants, the type scale
frontend/src/styles/primitives.css  `.lg-panel`, the one bordered surface, with the section edge
frontend/src/app/core/            api seams, stores, models, theme, shell
frontend/src/app/layout/          shell, header, nav rail, theme toggle
frontend/src/app/shared/          icon, brand mark, score, funnel rail, badge, stat tile,
                                  empty state, page header, the day pipe
frontend/src/app/features/        dashboard, shortlist (+ offer card, sort menu, facet
                                  panel, saved views), offer detail, pipeline, review,
                                  sources, rules. Shortlist, pipeline and review are split
                                  views — decisions/frontend-split-views.md
frontend/src/app/core/filter-views/  saved views: a name and a query string, in this
                                  browser's localStorage — decisions/frontend-split-views.md
frontend/tools/build-favicon.sh   renders the favicon set plus the 192/512 round-plate,
                                  512 maskable and 180 touch icons from brand/mark.svg
```

The two Python scripts are the **reference implementation**. Whatever they do, the Java
code has to reproduce — the numbers in `docs/SAMPLE-ANALYSIS.md` are the target values.

## Measured baseline

The numbers this tool was built against — what the corpus contains, what the newsletter never
carries, and what the hard filter lets through at which setting — are in
[`docs/SAMPLE-ANALYSIS.md`](docs/SAMPLE-ANALYSIS.md) § 8. They are measurements rather than
rules, and a measurement is re-taken rather than remembered.

## Order of work

Sixteen steps, all of them done: the skeleton, the configuration layer, ingest and the IMAP
connector, dedupe, the hard filter, enrichment, scoring and the digest, packaging, the
frontend, manual status capture, manual entry, the split views, content segmentation, the
three extracted fields, and the shortlist's filters. What each step had to prove before the
next one started is in `docs/decisions/order-of-work.md`. The file inventory below says what
came out of them.

## Traps that have already cost money

The list is split by tree: `backend/CLAUDE.md` and `frontend/CLAUDE.md` each carry their own,
and each arrives with the code it is about. One trap belongs to neither, because it fires on
an editor command aimed at the whole repository.

- **Reformatting an applied migration takes every deployed database down.** Flyway hashes the file's bytes, so
  realigning a column list or moving a `(` to its own line changes the checksum of a migration that ran months ago, and
  the application refuses to start with a mismatch per version rather than with anything naming the commit. Measured:
  one "Reformat Code" across the repository touched thirteen of sixteen migrations and stopped the microk8s deployment dead.
  The repair is to restore the files, not to repair the database, because the checksum has to match on every environment
  at once.
  `.editorconfig` switches the IntelliJ formatter off for `db/migration/*.sql` for exactly this reason.
## Settled

- **License: Apache-2.0.** `LICENSE` and `NOTICE` at the root, SPDX headers on the Java sources, and Spotless
  writes the header onto a new Java file rather than asking you to. It was off for a while and is on again since the
  reformat in `c3fc67c`; a formatting disagreement is settled with one reviewed `spotlessApply`, never with
  `-x spotlessCheck`.
- **The repository is `codeministry/leadgen`**, which is why the Java package
  `de.codeministry.leadgen` stays as it is.
- **No Helm chart in the repository.** Docker Compose is the supported way to run this;
  a chart is a later phase and the README no longer claims one.
