<img src="brand/leadgen.png" alt="LEADgen / AI" height="28">

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

By exact normalized title alone: **180 of 1289 offers are duplicates (14.0 %)**, leaving 1109
distinct titles. A single project appears up to eight times, often across three portals.

It was 159 until `TitleNormalizer` learned to strip `<mark>`. Some sources wrap the
subscriber's own search terms in it and the tag reaches the title as text, where removing the
angle brackets leaves the word `mark` standing — so one advert wearing it appeared six times
across two portals without ever meeting itself. Adding the one comparable field that does
exist, the stated location, collapses 127 rather than 180, which is why the fingerprint is the
title alone.

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
| No core skill | 426 | 33.0 % |
| Foreign stack or wrong role | 256 | 19.9 % |
| Contract form rejected | 47 | 3.6 % |
| Abroad (CH, AT, …) | 25 | 1.9 % |
| **remaining** | **535** | **41.5 %** |

Measured at `min_remote_percent: 0`, which switches the reach rule off: no required remote
share means on site is acceptable, and then it is acceptable anywhere. At 80 with a 21-day
window it was 239 and 18.5 %, and the stage that removed the most was reach, at 717.

**These numbers move with the rules, and that is why nothing keeps them by hand any more.**
`python3 docs/samples/simulate_filter.py` reads `config/` — the same two files the Java
filter reads — and writes `docs/samples/filter-baseline.json`; the corpus test asserts
against that. Change a threshold, run the script, and both sides follow together.

After deduplication 207 unique offers remain, so **about 16 per mail**.

> **These numbers changed on 2026-09-01.** The first run of this table read 213 and
> 16.5 %, and three defects in the simulation were behind the difference — all three
> silent, all three in how text was compared:
>
> 1. **Umlauts were broken, not folded.** NFKD decomposed "Köln" and the character
>    filter then deleted the combining diaeresis *in place*, leaving `ko ln`, which
>    matches neither `köln` nor `koln`. **35 offers in Köln and 19 in Düsseldorf** — the
>    two cities nearest the home base — were being discarded as out of reach.
> 2. **Keywords were substrings, not words.** `ch` for Switzerland also matched Aachen
>    and Bochum and rejected 127 German offers as abroad; `essen` also matched Hessen and
>    accepted six offers from 200 km away; `ANÜ` also matched Planung, Manufacturing and
>    manuellen.
> 3. **Patterns were compared unfolded against folded text.** `.net` and `c#` matched
>    nothing at all, because the text no longer contained `.` or `#`.
>
> The core-skill list also grew from a hand-picked six to the profile's core skills and
> their aliases, which is what the Java reads; that is worth twelve offers naming Spring
> Cloud, OpenAPI or k8s. Every list now lives in `config/matching-rules.yaml` and
> `config/skill-profile.yaml`, and the simulation mirrors them.

That is the number that matters: 16 LLM assessments per day instead of 100. The
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

## 8. The baseline in one place

Moved here out of `CLAUDE.md`, which is loaded into every session and has a character budget
that `WorkingNotesStaySmallTest` enforces. These are measurements, not rules: the rule is
"re-measure before concluding a field is empty", and it stays in the notes. Everything behind
it is here.

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
- **14.0 % duplicates** by exact title alone, within a single mail.
