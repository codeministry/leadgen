# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project intends to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) —
"intends", because while the version is below `1.0.0` the configuration schema and the API
may change in any release. See the status note in the README.

## [Unreleased]

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
