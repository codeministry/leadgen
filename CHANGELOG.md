# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project intends to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) —
"intends", because while the version is below `1.0.0` the configuration schema and the API
may change in any release. See the status note in the README.

## [Unreleased]

### Fixed

- **The scoring stage was one transaction, and it blocked every other run.** It held a write lock on each offer it had
  judged until the last one was answered — with a local model, hours — and any concurrent pass's filter stage, which
  writes a verdict on every row with no `WHERE`, waited behind it. Measured on the cluster: two filter updates blocked
  for thirteen minutes behind a scoring transaction open for twenty, advancing one offer every 33 s, with a third run
  stacked behind those. The boundary is now one offer, one transaction, which also means a run that dies halfway keeps
  the scores it produced instead of none.
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

### Added

- **A dev-image track, between "it built" and "it was released."** `ci.yml` builds both
  images on every push and deliberately does not push them; `release.yml` pushes only on a
  `v*` tag and writes a release from this file. Getting an intermediate state in front of a
  cluster therefore meant tagging, changelogging and releasing — the wrong ceremony for a
  build nobody is announcing. `dev-image.yml` publishes one for every green `main` as
  `<next patch>-dev.<commits since the tag>-g<sha>` plus a floating `main`, creating no git
  tag and no release. It runs on `workflow_run` after CI rather than on `push`, so an image
  never exists for a commit whose gate went red, and the gate is not paid for twice.
- **`leadgen.version`, so the header names the build it is.** `StatusController` has always
  read `${leadgen.version:0.1.0}` and the property existed nowhere — not in
  `application.yaml`, not in the chart, and the Gradle version is not injected into the jar
  (no `buildInfo()`, no manifest entry, and the Dockerfile copies `libs/*.jar` by glob). So
  every image reported `0.1.0` whatever it was. The deployment sets it from the image tag.

## [0.1.2] — 2026-09-05

### Fixed

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

[Unreleased]: https://github.com/codeministry/leadgen/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/codeministry/leadgen/releases/tag/v0.1.0
