# lead-generation — Concept

Last updated: 2026-09-01

A tool for freelance project acquisition: it collects offers from arbitrary sources,
filters them against a profile and assembles a ready-to-send application package for
the matches. Sending stays manual, deliberately.

Measured baseline: [SAMPLE-ANALYSIS.md](SAMPLE-ANALYSIS.md).
How the enrichment stage came about: [CONCEPT-addendum-enrichment.md](CONCEPT-addendum-enrichment.md).

**Guard rail:** this project is meant to be published as open source. No newsletter,
no portal, no mail provider and no personal datum is wired into the code. Everything
individual lives in `config/local/` and in environment variables.

## 1. Decisions

| Topic | Decision |
|---|---|
| Repository | monorepo: `backend/`, `frontend/`, `charts/`, `config/`, `docs/` |
| Backend | Spring Boot (modular monolith), Java 21, Gradle |
| Frontend | Angular + NGRX + DaisyUI |
| Persistence | PostgreSQL |
| Sources | declaratively configured: IMAP, RSS/Atom, HTTP, file drop |
| Scoring | deterministic hard rules first, then an LLM (provider interchangeable) |
| Operation | Docker Compose locally → microk8s via Helm |
| Sending | draft only, the user sends |
| Language | repository is English throughout; the target market is German, which is content |

## 2. Monorepo layout

```
lead-generation/
├── backend/           Spring Boot, Gradle
├── frontend/          Angular + NGRX + DaisyUI
├── charts/            Helm chart for microk8s
├── config/
│   ├── examples/      neutral example configuration (in the repo)
│   └── local/         own data, profile, rules (gitignored)
├── docs/
├── docker-compose.yml
└── settings.gradle    root build brackets :backend and :frontend
```

One `./gradlew build` builds both; the frontend is wired in through the Node Gradle
plugin and lands as static resources in the backend image.

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
Which combination applies to which source lives exclusively in `config/local/sources.yaml`
— see `config/examples/sources.example.yaml`.

A new offer source is therefore a YAML block, not code and not a deploy. Deterministic
field extraction (CSS selector, regex, JSON path) runs first; the language model only
fills the gaps.

## 4. Pipeline

```
Sources ─▶ Ingest ─▶ Extract ─▶ Normalize ─▶ Dedupe ─▶ Filter ─▶ Enrich ─▶ Score ─▶ Package ─▶ Track ─▶ Digest
                                                                    ▲
                                                      only the ~16 % that survived
                                                      the hard filter
```

Measured against 14 real mails, see [SAMPLE-ANALYSIS.md](SAMPLE-ANALYSIS.md).

| Stage | Job | Configured through |
|---|---|---|
| Ingest | fetch sources, schedule per source | `sources.yaml` |
| Extract | one document → n offers | `sources.yaml → extraction` |
| Normalize | free text → structured `Offer`, only where structure is missing | LLM schema, model freely selectable |
| Dedupe | merge the same project coming through several agencies | `matching-rules.yaml → deduplication` |
| Filter | knockout criteria, no LLM call | `matching-rules.yaml → hard_filters` |
| Enrich | fetch the original ad: rate, duration, workload, full text | `application.yaml → enrichment` |
| Score | semantic comparison against the profile, with reasons | `matching-rules.yaml → scoring` |
| Package | assemble the application package | `application.yaml → packaging`, Freemarker templates |
| Track | status, follow-up, funnel | `matching-rules.yaml → follow_up` |
| Digest | one daily overview instead of reviewing each offer | `application.yaml → digest` |

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
backend/src/main/java/.../leadgen/
  ingest/       SourceConnector implementations
  extract/      ExtractionStrategy implementations
  normalize/    structured extraction, enforced JSON schema
  enrich/       HTTP fetch of the original ad, rate limit, cache, robots.txt
  matching/     rule engine (YAML-driven, hot-reloadable) + LLM scoring
  dedupe/       fingerprint, embedding comparison
  packaging/    document selection, cover letter, folder/ZIP
  tracking/     lifecycle, follow-up
  digest/       daily report, interchangeable transport
  api/          REST for the frontend
  config/       loading and validating the YAML configuration
```

## 7. Profile and rules

Two files, both outside the repository:

- `config/local/skill-profile.yaml` — skills with weights and synonyms, roles, industries,
  reference projects as raw material for cover letters, document variants per language.
- `config/local/matching-rules.yaml` — hard knockout criteria (remote share, region and
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

- **Phase 1:** `docker-compose.yml` (postgres, api, web), scheduler inside the backend.
- **Phase 2:** Helm chart in `charts/`, deployed to microk8s on the local network,
  secrets following the convention in `devops/SECRETS.md`.
- Remote access later through Tailscale (`devops/TAILSCALE_SETUP.md`).
- Authentication is configurable: `none` for local-only, `basic` or `oidc`/Keycloak.

## 11. Order of work

1. **Profile & rules** — `config/` ✅ done, verified against real mails
2. **Monorepo skeleton** — root build, backend skeleton, frontend skeleton, Compose
3. **Configuration layer** — load YAML, validate, hot-reload
4. **Ingest + extract** against the `local-eml` source — acceptance test: 1289 offers
   from `docs/samples/emails/`, field coverage as in the analysis
5. **IMAP connector** — same extraction, progress via `UIDVALIDITY`/`UID`
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

- **Mail access:** Strato IMAP (`imap.strato.de:993`, SSL), credentials via `.env`.
- **Two mailbox modes**, both implemented, switchable by a flag:
  - *filter mode* (active): the newsletter sits in a mixed folder, selected by sender and
    optionally subject.
  - *dedicated mode* (later): a separate mailbox that Strato delivers the newsletter into
    by rule — then `match_all` applies and no filter is needed.
  - Progress is tracked via `UIDVALIDITY`/`UID`, not via seen/unseen. The mails stay
    untouched and a second client does not interfere.
- **Hard filters:** at least 80 % remote, within 120 km of Bedburg, no exceptions outside
  that radius, country allowlist DE only, no temporary-employment or permanent contracts.
- **LLM:** Anthropic API, key via `.env`. A small model for extraction, a larger one for
  scoring and writing.
- **Documents:** fixed PDFs, language selection only. No per-offer tailoring, no
  integration with `cvfy`. The files live in `config/local/documents/`.

## 13. Open

- Adopt the code conventions from `codeministry/customer/ship360` — see `CLAUDE.md`
- License for publication — Apache 2.0, like `straightmail`?
- Repository name and GitHub organisation
- Sender address and folder of the newsletter in the Strato mailbox
