# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project intends to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) —
"intends", because while the version is below `1.0.0` the configuration schema and the API
may change in any release. See the status note in the README.

## [Unreleased]

## [0.2.1] — 2026-09-07

### Added

- **Archiving in bulk, from the shortlist.** Taking twenty offers off the working list cost twenty round trips and
  twenty confirmations. A card now carries a checkbox — the first control it has ever had — a Shift-click spans a range,
  and one action archives the selection behind a native `<dialog>`. `POST /api/offers/archive` is the endpoint, and it
  is the one place in this application where the plural breaks the singular's rule: `PATCH /api/offers/{id}` answers
  with the whole `ShortlistEntry` because the browser replaces its row with what the server stored, and the list does
  not replace an archived row, it drops it. Entries would be fetched for the sole purpose of being discarded, at roughly
  1.4 KB each. So the answer is a report — `requested`, `archived`, `unscored` — and the endpoint answers `200` where
  the single `PATCH` answers `404`: refusing fifty decisions because one id named no offer loses forty-nine for a reason
  nobody can act on.

  The selection lives in `ShortlistStore` and not in the query string, unlike every filter. Fifty ids in a URL is not a
  link anybody sends, and it would make the back button undo a checkbox. It clears on `opened`, which already fires on
  every filter change, so the picks cannot drift from the list they point into; `moreLoaded` deliberately writes
  nothing, which is what keeps a selection across paging and lets a Shift-range cross a page boundary. The anchor is an
  **id** rather than an index, because an index points at a different offer after every load-more.

  There is no bulk restore, which is why `ArchiveRequest` names no direction. A `boolean` with exactly one legal value
  is the class of thing this repository already removed twice — after `remote.accept_unknown` and the `onsite_max_km`
  key nothing read.

- **Two more content rules in the shipped `pipeline.yaml`**, both anchored to a whole block: the portal's two row
  actions, which arrive as exactly `Print Report`, and the report dialog's heading. Neither word is safe on its own — a
  bare `report` matches an advert asking for reporting experience, and `print` one about print media.

### Changed

- **The build is on Java 25.** It is the current LTS and the version Spring Boot 4.1 states support for. JaCoCo moved to
  0.8.13 with it, because a JaCoCo that cannot read the class-file version reports coverage of nothing rather than
  failing. See **Upgrading** — this one is not free for anybody building from source.

### Upgrading

**Building from source now needs a JDK 25 on the machine.** `settings.gradle.kts` has no toolchain resolver, so Gradle
cannot download one, and the failure is `No matching toolchains found` rather than anything naming a version. The
published images already carry it; nothing about a running deployment changes, and there is no migration.

The two new content rules ship in the classpath `pipeline.yaml`. The two configuration layers override **file by file**,
so a `pipeline.yaml` of your own does not receive them — copy the two `content.rules` entries across, or the portal's
report dialog stays in the advert and stays in what the judge is asked about.

## [0.2.0] — 2026-09-06

The scoring half changed shape here: a total is a share of what an advert made attainable rather than a sum over every
weight, so a number written by 0.1.x and a number written by 0.2.0 are not the same measurement. See **Upgrading**.

### Added

- **Split views.** Reading an offer used to cost the list — the shortlist, the board and the review queue each replaced
  their list with the thing being read. All three now keep the list on the left and open what is selected on the right,
  in two columns that scroll independently under a screen bounded to the viewport. The shortlist and the board open a
  child route on a real component; the review holds the file name in a query parameter, because its document is already
  in the store the screen reads. `/offers/:id` redirects into the shortlist's split view, so there is one detail view in
  the code rather than two.
- **Keyboard navigation in the shortlist.** `j`/`k` and the arrow keys move between entries, and past the last loaded
  one the key asks for the next page and stays put. The handler is bound to the list pane rather than to the document,
  so `j` stays a letter in the search field and the arrow keys still scroll the advert while the reader is in the detail
  column.
- **The status picker carries a visible label**, on the board and in the offer detail. A bare select beside a badge
  stated an application's state twice and named it neither time.
- **A dev-image track, between "it built" and "it was released."** `ci.yml` builds both
  images on every push and deliberately does not push them; `release.yml` pushes only on a
  `v*` tag and writes a release from this file. Getting an intermediate state in front of a
  cluster therefore meant tagging, changelogging and releasing — the wrong ceremony for a
  build nobody is announcing. `dev-image.yml` publishes one for every green `main` as
  `<next patch>-dev.<commits since the tag>-g<sha>` plus a floating `main`, creating no git
  tag and no release. It runs on `workflow_run` after CI rather than on `push`, so an image
  never exists for a commit whose gate went red, and the gate is not paid for twice.
- **`leadgen.version`, so the header names the build it is.** `StatusController` has always read `${leadgen.version:…}`
  and the property existed nowhere — not in `application.yaml`, not in the chart, and the Gradle version is not injected
  into the jar (no `buildInfo()`, no manifest entry, and the Dockerfile copies `libs/*.jar` by glob). So every image
  reported the literal default whatever it was. The deployment sets it from the image tag.

### Changed

- **A score is a share of what was attainable, not a sum over every weight.** A factor the advert said nothing about
  writes no reason at all and is in neither half of the fraction; a factor that had something to say and scored badly
  writes a 0-point row and stays in the denominator. `offer_score_reason` carries `max_points` for that, so a screen can
  read
  "23 / 45". Summed instead, the scale was capped by things no advert could influence: the sources state a rate in 0.0 %
  of offers, so all 101 scored offers carried a 0-point
  `rate_fit`, `industry_fit` fired zero times, `project_setup` averaged 0.2 of 10, and the highest score in the whole
  table was 53. The accepted consequence is that the less an advert states, the more its skill overlap carries — an
  offer is judged on what it says.
- **Skill overlap is weighted and saturates; it is no longer a count.** `matched.size() /
  core.size()` ignored the per-skill weights and read neither `strong:` nor `peripheral:`, despite the weight table's
  own comment saying otherwise — so an advert asking for Kafka, PostgreSQL, Keycloak and CI/CD scored nothing for four
  things the profile is strong in, and a backend advert was charged for not naming Angular. The matched weights are
  added up (peripheral at half) and measured against the `scoring.saturation_core_count` heaviest core skills, because
  no advert names a whole profile and requiring one requires something that never happens. Over the corpus the factor
  never once exceeded five of eight core skills.
- **A composite skill name is split on `/` before matching, and an industry is matched through `match:` rather than
  through its name.** Folding keeps a name whole, so
  `REST / API-Design` was the phrase `rest api design` and matched only an advert writing it in that order — no advert
  does. And the profile names an industry in this repository's language while the adverts are German, so `Insurance` was
  compared against text that says *Versicherung*. Same shape and same reason as a skill's aliases; the name is still
  tried, so a profile written before this behaves as it did.
- **The judge is given the profile it is judging against.** The prompt used to say "a senior Java, Spring Boot and
  Angular developer who works from Germany" — three skills of the twenty-nine in `skill-profile.yaml`, hard-coded, while
  every other stage read the file. Role fit was judged against a description of somebody else, and editing the profile
  could not move it. The offer's rate, duration, workload and start go into the description too: those come from
  enrichment rather than from the advert's prose, and a judge calling an offer vague while the row beside it states all
  four knows less than the application does.

### Fixed

- **An offer the judge never answered for still carried a total.** `role_fit` is the one factor the prompt requires even
  at zero, so its absence is an unreachable endpoint, a reply that was not JSON, or a model that ignored the
  instruction — never an opinion. Measured before `Judge.answered` existed: 63 of 101 scored offers had no judged factor
  at all, and every one of them carried a number that looked like all the others. Such an offer is left unscored now,
  which is self-healing: a null `score_model` makes it due again, and `ScoringReport.unusable` puts it in the run's own
  log.
- **`max_per_run` did not bound how long a pass waits, so a backlog never cleared.**
  Refusing rather than waiting is right for the rate limiter and wrong for the pass on top of it: a run did one minute's
  worth of fetching and deferred the rest, so a backlog needed one run per `rate_limit_per_minute` offers to clear, and
  it never got them. Measured on the live database: **2,537 offers carried `enrichment_note = 'rate limit reached'` with
  a stamped `enriched_at`**, and only 23 rows in the whole table had a `full_text`.
  `AdFetcher` now waits for a permit the window would have granted anyway, up to the run's budget, and refuses beyond
  it. Unset means the old behaviour, so a configuration written before this key behaves as it did.
- **A rate-limited fetch was written off permanently.** The limiter refuses rather than
  waits, and the refusal was recorded like a failed fetch — which stamps `enriched_at`,
  and the due query is `enriched_at IS NULL`. The offer was therefore never fetched again
  and went on to be scored on the newsletter summary alone, with no rate, no duration and
  no full text. Measured on the first full pass against a real mailbox: 480 due, 20
  fetched, **460 written off with "rate limit reached" and 0 left due.**

  The cache already draws this line — "failures are cached, timeouts are not", because a
  403 is a fact about the page and a timeout is a fact about the moment. A refusal from the
  limiter is the second kind: it says this run has asked this portal often enough, which is
  true of the minute and of nothing else. `FetchResult.deferred` now says so, nothing is
  written for those offers, and they are due again on the next pass.
  `EnrichmentReport.deferred` reports them separately from `incomplete`, because the whole
  difference between the two is whether the offer comes back.
- **The scoring stage was one transaction, and it blocked every other run.** It held a write lock on each offer it had
  judged until the last one was answered — with a local model, hours — and any concurrent pass's filter stage, which
  writes a verdict on every row with no `WHERE`, waited behind it. Measured on the cluster: two filter updates blocked
  for thirteen minutes behind a scoring transaction open for twenty, advancing one offer every 33 s, with a third run
  stacked behind those. The boundary is now one offer, one transaction, which also means a run that dies halfway keeps
  the scores it produced instead of none.
- **The enrichment stage was one transaction too**, and it became the worse of the two the moment that stage started
  waiting for rate-limit permits by design: a pass holding a write lock on every offer it had touched, for minutes, with
  any concurrent filter stage sitting behind it. Each result is one statement and nothing there needs atomicity across
  offers.
- **The dashboard listed every source twice while a pass was running.** `source_run` has no run id, so its rows are
  addressed by time, and the window had a lower bound and no upper one — on the reasoning that "no later run exists to
  contribute rows above it", which holds only while nothing else is running. A run opens its `pipeline_run` row when it
  starts now (`RUNNING`, zeros, no `finished_at`), so the next run's `started_at` is knowable and becomes the bound.
  That column is the right one because it never moves;
  `finished_at` is pushed forward by the batch collector. The old placement's intent survives — a row that claims
  nothing cannot claim a clean pass, and a run that dies leaves it saying `RUNNING`, which is more honest than leaving
  no trace.
- **`POST /api/ingest` had no concurrency guard, so a second pass simply queued in the database.** It now answers `409`
  and starts nothing. The CronJob's
  `concurrencyPolicy: Forbid` never covered this: it governs only the jobs the CronJob itself creates, and the button is
  how a run is normally started.

### Upgrading

The schema migrates itself (`V16` adds `offer_score_reason.max_points`), but **the scores already in the table do not**:
a total written by 0.1.x is a sum over every weight, a total written by 0.2.0 is a share of what the advert made
attainable, and the shortlist threshold is one number read against both. Nothing recomputes them by itself either — a
run judges what is stale, and stale means never written, a different `ruleset_version` or a different
`score_model`, none of which this release changes on its own.

Bump `version:` in your `matching-rules.yaml` once after upgrading to put the standing list back on one scale. That is
one full pass at the configured model's rate, so check
`hard_filters.freshness.max_age_days` and the archive first: everything outside that window is filtered and archived
before the stage that costs money is reached.

## [0.1.1] — 2026-09-05

### Fixed

- **The IMAP source handed over nothing from a mailbox its owner reads.** Spring
  Integration's default `SearchTermStrategy` does not express "not already taken" in terms
  of the user flag alone — it also excludes every message carrying `\Seen`. In the mailbox
  this tool is pointed at, that is every message the owner has opened, so a run reported
  zero documents with no error anywhere. Measured against a real mailbox: 165 mails in the
  folder, 165 matching `NOT KEYWORD leadgen`, 0 matching the default term.

  Not marking `\Seen` is pointless if progress is read off it, and "fewer offers" is
  indistinguishable from a quiet day on the market — which is why this was invisible.
  `ImapSourceConnector` now supplies its own search term: not deleted, and not carrying the
  `leadgen` user flag. Every existing test delivered a fresh, unseen mail, so none of them
  could see it; the new one marks the message read first.

### Upgrading

Nothing to configure. The first run after this release hands over the whole backlog the
old search term was hiding, bounded only by the source's `selector.since_days` — on the
mailbox this was measured against, 138 newsletters announcing 14,241 offers, where every
previous run had reported zero. That pass is long and it is a one-off; the runs after it
see only what has arrived since.

What it costs is decided by `hard_filters.freshness.max_age_days`, not by `since_days`.
The archive pass sits between the hard filter and enrichment, so everything older than
that window is read, deduplicated, filtered and archived without ever reaching the stage
that leaves the machine or the one that calls a language model. Check that number before
the first run rather than after it.

## [0.1.0] — 2026-09-02

The first public release. Everything below already existed; this is the point at which it
became readable by somebody who did not write it.

### Added

- **The pipeline, end to end.** Ingest from files and IMAP, declarative extraction, an
  upsert that makes re-reading a newsletter free, deduplication of one project advertised
  by several portals, six deterministic knockout stages, an age archive, enrichment of the
  original ad, deterministic scoring with an optional language-model judge, an application
  package per shortlisted offer, and a digest written to a file.
- **Manual entry and its review.** A Markdown file uploaded through the browser waits in
  `inbox/pending/` until somebody has seen what was read from it; confirming writes the
  corrected frontmatter back and moves the file where the source is already looking.
- **Manual status capture.** Eleven application states across five lanes, every change
  recorded as an event, because the half of the loop that involves a human is the half the
  tool cannot observe.
- **Seven screens**, all on real endpoints: dashboard, shortlist, offer detail, pipeline
  board, analytics, sources, rules and review. Two themes, two languages, English the
  fallback.
- **A demo dataset** under `demo/` — an invented corpus, profile and rule set, so a fresh
  clone opens on a populated application. `docker compose -f docker-compose.yml -f
  docker-compose.demo.yml up`.
- **Apache-2.0 licence**, SPDX headers on every Java source, enforced by Spotless rather
  than remembered.
- **Coverage reporting** — JaCoCo for the backend, v8 for the frontend, both on `check`.
- **Spring portfolio where it earns its place.** The judges go through Spring AI's
  `ChatClient`, so two hand-written wire formats become none; the ad fetcher goes through
  `RestClient` with Framework 7's `RetryTemplate`, closing a gap where there had been no
  retry at all; the IMAP source goes through Spring Integration's `ImapMailReceiver`; and
  every run now records what each of its stages cost, in `pipeline_stage`.
- **Deliberately not adopted, each for a measured reason.** Spring Batch, because the
  pipeline has no chunk work and no restart requirement and would have gained nine tables of
  foreign DDL; Resilience4j's rate limiter, because it is a fixed window where this needs a
  sliding one; and Spring's cache abstraction for the page cache, because it has no JDBC
  provider and no per-entry TTL.

### Fixed

Found while building the demo, all of them in paths only a container exercises:

- `MANUAL_INBOX_DIR` was never set under Compose, so its default resolved inside the
  read-only `/config` mount and every upload failed with a permission error naming a path
  nobody had configured.
- `DIGEST_DIR` likewise defaulted to a directory under the container's working directory,
  which the non-root user cannot write. A run completed every stage and died on the last.
- The Anthropic judge discarded every answer that arrived wrapped in a Markdown fence,
  which was all of them on the model tested. The effect was four missing factors on an
  offer that looked judged, and one warning per offer as the only sign. The braces now
  decide, and the warning carries the text it could not read.
- `sources.yaml` required a `connections:` block. A configuration whose sources are all
  files has no credentials to carry, and leaving the key out failed the whole file at
  startup several frames away from the source that needed no connection.
- The dashboard's filter panel said "seven stages" after `STALE` moved to the archive and
  left six.
- The shortlist card printed the description's Markdown syntax in its teaser.

[Unreleased]: https://github.com/codeministry/leadgen/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/codeministry/leadgen/releases/tag/v0.2.1
[0.2.0]: https://github.com/codeministry/leadgen/releases/tag/v0.2.0
[0.1.1]: https://github.com/codeministry/leadgen/releases/tag/v0.1.1
[0.1.0]: https://github.com/codeministry/leadgen/releases/tag/v0.1.0
