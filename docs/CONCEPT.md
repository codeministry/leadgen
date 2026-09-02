# lead-generation — Concept

Last updated: 2026-09-01

A tool for freelance project acquisition: it collects offers from arbitrary sources,
filters them against a profile and assembles a ready-to-send application package for
the matches. Sending stays manual, deliberately.

Measured baseline: [SAMPLE-ANALYSIS.md](SAMPLE-ANALYSIS.md).
How the enrichment stage came about: [CONCEPT-addendum-enrichment.md](CONCEPT-addendum-enrichment.md).

**Guard rail:** this project is meant to be published as open source. No newsletter,
no portal, no mail provider and no personal datum is wired into the code. Everything
individual lives in `config/` and in environment variables.

## 1. Decisions

| Topic | Decision |
|---|---|
| Repository | monorepo: `backend/`, `frontend/`, `config/`, `docs/` |
| Backend | Spring Boot (modular monolith), Java 21, Gradle |
| Frontend | Angular + NGRX + DaisyUI |
| Persistence | PostgreSQL |
| Sources | declaratively configured: IMAP, RSS/Atom, HTTP, file drop |
| Scoring | deterministic hard rules first, then an LLM (provider interchangeable) |
| Operation | Docker Compose; a Helm chart is a later phase and not in the repo |
| Sending | draft only, the user sends |
| Language | repository is English throughout; the target market is German, which is content |

## 2. Monorepo layout

```
lead-generation/
├── backend/               Spring Boot, Gradle
│   └── src/main/resources/leadgen/
│                          the shipped defaults — neutral, every value a ${PLACEHOLDER}
├── frontend/              Angular + NgRx signals + Tailwind/DaisyUI
├── config/                own data, profile, rules — overrides file by file (gitignored)
├── docs/
├── docker-compose.yml
└── settings.gradle.kts    root build brackets :backend and :frontend
```

One `./gradlew build` builds both. The frontend is bracketed with plain `Exec` tasks
calling `bun`, not with the Node Gradle plugin — the plugin does not speak bun, and
`package.json` stays the single list of frontend commands. The two modules ship as two
images; nginx serves the SPA and proxies `/api/` to the backend.

## 3. Source abstraction

The core knows exactly two interfaces:

```java
interface SourceConnector {          // yields RawDocuments
    Flux<RawDocument> fetch(SourceConfig config);
}

interface ExtractionStrategy {       // RawDocument -> 0..n RawOffer
    List<RawOffer> extract(RawDocument doc, ExtractionConfig config);
}
```

Implementations: `ImapConnector`, `RssConnector`, `HttpJsonConnector`, `FileDropConnector`,
and `SingleStrategy`, `HtmlBlockStrategy`, `RegexSplitStrategy`, `LlmStrategy`.
Which combination applies to which source lives exclusively in `config/sources.yaml`
— see `backend/src/main/resources/leadgen/sources.yaml`.

A new offer source is therefore a YAML block, not code and not a deploy. Deterministic
field extraction (CSS selector, regex, JSON path) runs first; the language model only
fills the gaps.

## 4. Pipeline

```
Sources ─▶ Ingest ─▶ Extract ─▶ Normalize ─▶ Dedupe ─▶ Filter ─▶ Archive ─▶ Enrich ─▶ Score ─▶ Package ─▶ Track ─▶ Digest
                                                                                ▲
                                                                  only what survived the hard
                                                                  filter and is still on the list
```

Measured against 14 real mails, see [SAMPLE-ANALYSIS.md](SAMPLE-ANALYSIS.md).

| Stage | Job | Configured through |
|---|---|---|
| Ingest | fetch sources, schedule per source | `sources.yaml` |
| Extract | one document → n offers | `sources.yaml → extraction` |
| Normalize | free text → structured `Offer`, only where structure is missing | LLM schema, model freely selectable |
| Dedupe | merge the same project coming through several agencies | `matching-rules.yaml → deduplication` |
| Filter | knockout criteria, no LLM call | `matching-rules.yaml → hard_filters` |
| Archive | take aged-out offers off the working list, restore is manual | `matching-rules.yaml → freshness` |
| Enrich | fetch the original ad: rate, duration, workload, full text | `pipeline.yaml → enrichment` |
| Score | semantic comparison against the profile, with reasons | `matching-rules.yaml → scoring` |
| Package | assemble the application package | `pipeline.yaml → packaging`, Freemarker templates |
| Track | status, follow-up, funnel | `matching-rules.yaml → follow_up` |
| Digest | one daily overview instead of reviewing each offer | `pipeline.yaml → digest` |

**Cost logic:** the hard filter runs before every expensive call. Only what survives gets
scored semantically. Extraction uses a small model, scoring and writing a large one.
Results are cached per document id — the same mail is never paid for twice. The LLM
provider is interchangeable (Anthropic, Ollama, OpenAI-compatible); in the extreme the
tool runs purely rule-based with no external call at all.

## 5. Domain model

```
RawDocument        source_id, external_id, raw, fetched_at, processed_at
  └─ RawOffer      the slice of one offer within a document
       └─ Offer    canonical: title, description, skills[], city, remote_percent,
                   start_date, duration, rate, language, industry, contact, url, fingerprint
            ├─ OfferSource   channel, sender, url, first_seen   (n per Offer = duplicate cluster)
            ├─ Score         hard_pass, value, reasons[], model, ruleset_version
            └─ Application   status, cv_variant, cover_letter, sent_at, follow_up_at, outcome
Digest             date, offers[]
```

`Application` state machine:
`NEW → SHORTLISTED → PACKAGED → SENT → REPLIED → INTERVIEW → OFFER → WON | LOST | REJECTED | EXPIRED`

The status list is configuration, not an enum chain in the code — a different workflow
should be possible without a fork.

## 6. Backend module layout

```
backend/src/main/java/de/codeministry/leadgen/
  config/       loading, validating and hot-reloading the four YAML files
    model/      the records that mirror those files
  ingest/       the pipeline orchestrator (IngestService)
    connector/  SourceConnector implementations — file, IMAP
    extract/    ExtractionStrategy implementations — html-blocks, markdown-frontmatter
    store/      the offer upsert and the IMAP cursor
  dedupe/       fingerprint clustering of one project across several portals
  filter/       the six deterministic knockout stages, no model and no network
  archive/      what is no longer on the working list, by age or by hand
  enrich/       HTTP fetch of the original ad — rate limit, cache, robots.txt
  score/        deterministic factors (RuleScorer) plus an optional Judge
  packaging/    document selection, cover letter, the folder on disk
  application/  manual status capture and its event log
  manual/       uploads waiting for review
  digest/       the daily file, text or HTML
  offer/        the read side of the shortlist
  analytics/    run history and market figures
  web/          the REST controllers, and nothing else
```

The layout mirrors the pipeline. There is no `api/` package: each controller sits in
`web/` and every stage owns its own slice, which is why a stage can be read on its own.

## 7. Profile and rules

Two files, both outside the repository:

- `config/skill-profile.yaml` — skills with weights and synonyms, roles, industries,
  reference projects as raw material for cover letters, document variants per language.
- `config/matching-rules.yaml` — hard knockout criteria (remote share, region and
  travel radius, minimum hourly rate, contract type, country allowlist), weighted scoring,
  a negative list for dominant foreign stacks, deduplication and follow-up rules.

Both are reloadable at runtime and editable through the UI later. Tuning without a deploy
is intentional: these criteria will change constantly in the first few weeks.

## 8. Application package

Each match produces a folder named by a configurable pattern:

- CV in the language of the ad (variant mapping lives in the profile)
- cover letter, tailored, with two or three matching reference projects
- the archived original text plus the extracted fields
- `meta.json` with score, reasons, rate, location, start, contact and every source of
  the duplicate cluster

## 9. Frontend

| View | Content |
|---|---|
| Dashboard | today's digest, funnel figures, follow-ups due |
| Shortlist | card list with score, reasons, source badges for duplicates |
| Offer detail | original text, extracted fields, match reasoning, cover-letter editor, download |
| Pipeline | kanban by status |
| Sources | create and test sources, hit rate per source |
| Profile & rules | maintain skills, weights, knockout criteria |

## 10. Operation

- **Phase 1, and what ships today:** `docker-compose.yml` (postgres, api, web). A run is
  triggered from the UI; whatever schedules the run schedules the digest.
- **Phase 2, not in the repo yet:** a Helm chart for a small Kubernetes cluster.
- Authentication is a schema with one implemented value: `none`. `basic` and `oidc` are
  rejected at load rather than silently ignored, and `server.address` defaults to
  `127.0.0.1` — that loopback bind is what stands in front of the write endpoints today.

## 11. Order of work

1. **Profile & rules** — `config/` ✅ done, verified against real mails
2. **Monorepo skeleton** — root build, backend skeleton, frontend skeleton, Compose
3. **Configuration layer** — load YAML, validate, hot-reload
4. **Ingest + extract** against the `local-eml` source — acceptance test: 1289 offers
   from `docs/samples/emails/`, field coverage as in the analysis
5. **IMAP connector** — same extraction, a different way of getting at the HTML
6. **Dedupe** — pulled forward: 12.3 % duplicates occur within a single mail
7. **Hard filter** — must hit the measured 16.5 %
8. **Enrichment** — without this stage there is no rate and no full text
9. **Scoring + digest** — first daily overview, still without a frontend
10. **Packaging** — cover letter and document selection
11. **Frontend** — shortlist, detail, pipeline
12. **More sources** — portal feeds, second platform, direct enquiries

Step 7 is the target-value test: the hard filter lets a measured 16.5 % through, about
15 offers per mail. If the Java code deviates, that is a bug and not a matter of taste —
the simulation in `docs/samples/simulate_filter.py` is the reference.

## 12. Settled configuration

- **Mail access:** IMAP mailbox (`imap.example.com:993`, SSL), credentials via `.env`.
- **Two mailbox modes**, both implemented, switchable by a flag:
  - *filter mode* (active): the newsletter sits in a mixed folder, selected by sender and
    optionally subject.
  - *dedicated mode* (later): a separate mailbox that the provider delivers the newsletter into
    by rule — then `match_all` applies and no filter is needed.
  - Progress is never tracked via seen/unseen: the owner reads the same mailbox on a phone,
    and a flag-based cursor would skip whatever they opened first. What replaced the original
    `UIDVALIDITY`/`UID` watermark is Spring Integration's own user flag — see
    [ARCHITECTURE.md](ARCHITECTURE.md) for what that traded away.
- **Hard filters:** a minimum remote share, a hand-drawn list of reachable cities rather
  than a radius, no exceptions outside
  that radius, country allowlist DE only, no temporary-employment or permanent contracts.
- **LLM:** provider and key via `.env`, never in a committed file. A small model for extraction, a larger one for
  scoring and writing.
- **Documents:** fixed PDFs, language selection only. No per-offer tailoring, no
  integration with `cvfy`. The files live in `config/documents/`.

## 13. Open

- Sender address and folder of the newsletter in the IMAP mailbox — deployment detail,
  and deliberately not in any committed file
