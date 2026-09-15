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

`backend/` (Spring Boot 4.1, Java 25, Gradle) · `frontend/` (Angular 22 zoneless +
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
| The working-set predicate, keyset paging, the six sort keys, the filters    | `docs/decisions/read-side.md`               |
| The two configuration layers and the startup banner                         | `docs/decisions/configuration.md`           |
| The three split screens, the shell, the write path                          | `docs/decisions/frontend-split-views.md`    |
| Both themes, the accent's one meaning, the navigation, the catalogs         | `docs/decisions/frontend-design-system.md`  |
| The eleven application states and their event log                           | `docs/decisions/manual-status.md`           |
| The sixteen steps this tool was built in, and what each had to prove        | `docs/decisions/order-of-work.md`           |

The conventions and the traps for each half sit beside the code, in `backend/CLAUDE.md` and
`frontend/CLAUDE.md`. A nested file is loaded when a file in that tree is read, never at
startup, so neither costs anything while you are working in the other one.

**Where a new rule goes.** The rule itself — one or two lines, the imperative — goes into the
file that is loaded when somebody could break it: this one if it is repo-wide, `backend/` or
`frontend/` if it belongs to one tree. Everything behind it — the measurement, what it cost,
why the obvious alternative was not taken — goes into the matching file in `docs/decisions/`.
Both halves are worth keeping; only one of them has to be in context at all times.

This is not a style preference, it is the correction of a measured failure. This file was
20,641 characters on 2026-09-01 and 166,477 two weeks later, because every decision landed
here whole. At that size it is loaded into every session, on every turn, and the rules that
matter are buried in the reasoning behind them. `WorkingNotesStaySmallTest` holds the split:
it fails when a file outgrows its budget, when a path named here does not exist, and when a
document in `docs/decisions/` is not reachable from the table above.

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
docs/decisions/*.md               the reasoning per stage, moved out of this file
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
frontend/tools/build-favicon.sh   renders favicon.ico, favicon-256.png and logo-mark.png
```

The two Python scripts are the **reference implementation**. Whatever they do, the Java
code has to reproduce — the numbers in `docs/SAMPLE-ANALYSIS.md` are the target values.

## Measured baseline

- 14 mails, **1289 offers**, all extracted deterministically via CSS. The count announced
  in the subject matches exactly in all 14. `fallback: none` for this source.
- **0.0 % contain an hourly rate.** Rate, duration, workload and start date only arrive
  from the enrichment stage (fetching the original ad from the portal).
- **0 of 1289 state an application deadline, and that is a property of the newsletter, not
  of the market.** Measured on the deployed instance after the first field-extraction pass:
  19 of 21 offers on the working list stated at least one of start, duration or deadline, **9 of them a deadline**,
  dates from 08.09. to 30.09., one already expired. A start is
  stated as a phrase far more often than as a day — 18 phrases, 4 resolvable to a calendar
  day, because "Oktober 2026" is a month and a month is not a day. This is the number to
  re-measure before concluding that a field is empty: the sample corpus under
  `docs/samples/` cannot show it, since the deadline only appears in the ad the enrichment
  stage fetches.
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
  one
  "Reformat Code" across the repository touched thirteen of sixteen migrations and stopped the microk8s deployment dead.
  The repair is to restore the files, not to repair the database, because the checksum has to match on every environment
  at once.
  `.editorconfig` switches the IntelliJ formatter off for `db/migration/*.sql` for exactly this reason.
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
