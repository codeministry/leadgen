# Analysis of the sample mails

Basis: 14 newsletter mails from `<newsletter-sender>`, subject `"N neue Projekte sind da!"`,
in `docs/samples/emails/`. Reproducible with the two scripts in the same folder.

Source names are anonymized throughout this document: the newsletter aggregator, its
sender address and host, and the portals behind it appear as `<newsletter-sender>`,
`<aggregator-host>` and `portal-a` … `portal-f`. The real names live in
`config/local/sources.yaml`, which is gitignored. Every figure below is the measured
one.

```bash
python3 docs/samples/analyze_samples.py    # extraction, field coverage, duplicates
python3 docs/samples/simulate_filter.py    # simulation of the hard filters
```

## 1. Extraction needs no language model

The newsletter is cleanly structured HTML. All 1289 offers were extracted through CSS
selectors, and the count announced in the subject matches exactly in all 14 mails.

| Field | Coverage | Selector |
|---|---|---|
| Title | 100.0 % | `h3.job-title` |
| Short description | 99.9 % | `div.job-description` |
| Link | 100.0 % | `a.job-link` (proxy, unwrap the `target` parameter) |
| Location | 99.5 % | `div.job-meta span` with prefix 📍 |
| Date | 100.0 % | `div.job-meta span` with prefix 📅 |
| Portal | 100.0 % | `div.job-meta span` with prefix 🔗 |
| Agency | 90.8 % | `div.job-meta span` with prefix 🏢 |
| Search tags | 100.0 % | `div.tag-group h2.tag-header` |

**Consequence:** the LLM extraction from the original concept is dropped for this source.
`fallback: none`. The language model is only needed for scoring and writing.

Two details the code has to account for:

- The links go through `<aggregator-host>/proxy?target=…&email=…`. The own mail address
  does not belong in the archive; the `target` parameter gets unwrapped, `email` discarded.
- Search terms are wrapped in `<mark>` inside the title. Strip them before any comparison,
  or deduplication trips over `<mark>DevOps</mark>`.

## 2. What the newsletter does *not* provide

| Field | Present in the newsletter |
|---|---|
| Hourly rate | **0.0 %** |
| Explicit remote share | 8.8 % |
| Duration, workload, start date | not present |

The description is not the original ad but a generated summary („Der ideale Kandidat
sollte …"). Too thin for a reliable assessment.

**Consequence:** a new pipeline stage **enrichment** between the hard filter and scoring.
For the ~15 offers per mail that survive the filter, the original ad is fetched from the
portal, and rate, duration, workload and full text are taken from it. The rate filter only
applies afterwards — placed earlier it would, at 0 % coverage, filter either everything or
nothing.

## 3. Source portals behind the aggregator

| Portal | Offers |
|---|---|
| portal-a | 1083 |
| portal-b | 152 |
| portal-c | 19 |
| portal-d | 12 |
| portal-e | 11 |
| portal-f | 7 |
| external | 5 |

The newsletter already covers portal-a. Adding a direct portal-a feed as a second
source mainly buys speed, not additional coverage — and the premium advertising inside
the newsletter confirms that free users are informed hours later than paying ones.

## 4. Duplicates

By exact normalized title alone: **159 of 1289 offers are duplicates (12.3 %)**. A single
project appears up to eight times, often across three portals.

```
8x  Fullstack Entwickler (m/w/d)                    [external, portal-a, portal-b]
6x  Azure Integration Architect                     [portal-a, portal-b]
6x  Linux-Systemadministration                      [portal-a, portal-b]
```

Fuzzy matching would push the figure higher. Deduplication is therefore not a "later"
topic — it pays off within a single mail.

## 5. The hard filter: the reality check

Simulated against all 1289 offers, using the criteria from `matching-rules.yaml`:

| Stage | Filtered out | Share |
|---|---|---|
| Location beyond 120 km and not remote | 666 | 51.7 % |
| Abroad (CH, AT, …) | 152 | 11.8 % |
| No core skill | 150 | 11.6 % |
| Foreign stack or wrong role | 97 | 7.5 % |
| Remote share below 80 % | 11 | 0.9 % |
| **remaining** | **213** | **16.5 %** |

After deduplication 195 unique offers remain, so **about 15 per mail**.

That is the number that matters: 15 LLM assessments per day instead of 100. The
enrichment fetches are in the same order of magnitude. Both cost cents per day.

## 6. What the hard filter still lets through

Sampling the survivors shows the expected false positives, slipping in through a core
skill mentioned somewhere in the text:

```
Junior Recommender Engineer – Data Science / Python / ML
Interim Senior Dynamics 365 CRM Developer
Scala Azure Databricks Developer
Middleware specialist IBM WebSphere Liberty
```

That is intentional. The hard filter is meant to be coarse and cheap; sorting these out
is the scoring stage's job. Tightening it here loses good matches with unusual titles.

One possible tightening, should 15 per day turn out to be too many: check the core skill
only against `Java`, `Spring` and `Angular` instead of also `TypeScript` and `Kubernetes`
— those are the terms most false positives come in through.

## 7. Search tags of the aggregator profile

The tags come from the search profile configured there and are already a pre-filter:

```
Cloud 656 · DevOps 363 · Kubernetes 360 · Java 335 · AI 297 · Docker 251
TypeScript 187 · PostgreSQL 160 · Spring Boot 142 · Angular 140
Machine Learning 50 · Data Science 46 · Elasticsearch 18 · MongoDB 11 · Redis 7
```

`AI`, `Machine Learning` and `Data Science` together produce close to 400 hits and almost
nothing suitable. Deselecting them in the aggregator profile removes noise at the source.
`Cloud`, with 656 hits, is so broad that it barely narrows anything.
