# Addendum: the enrichment stage

**Merged into `docs/CONCEPT.md`.** This document remains as the derivation: it explains
why the pipeline gained a stage after the fact and why the order of work changed.
Data basis: `docs/SAMPLE-ANALYSIS.md`.

## Change to the pipeline

Before:

```
Sources ─▶ Ingest ─▶ Extract ─▶ Normalize ─▶ Dedupe ─▶ Filter ─▶ Score ─▶ Package ─▶ Track ─▶ Digest
```

After:

```
Sources ─▶ Ingest ─▶ Extract ─▶ Normalize ─▶ Dedupe ─▶ Filter ─▶ Enrich ─▶ Score ─▶ Package ─▶ Track ─▶ Digest
                                                                    ▲
                                                      only the ~16 % that survived
                                                      the hard filter
```

## Why

The newsletter contains an hourly rate in **0.0 %** of offers, an explicit remote share in
8.8 %, and never a duration, workload or start date. The description is a generated
summary, not the original ad.

That leaves scoring without half its basis, and the rate filter with nothing to filter.
The original ad, however, sits behind the extracted link — one HTTP fetch per offer.

## Shape

- Runs **after** the hard filter, not before. With about 15 survivors per mail that is
  15 fetches instead of 100.
- A rate limit and a cache are mandatory; `robots.txt` is respected.
- Full text via readability, then fields by regex and, where necessary, by language model:
  rate, duration, start date, remote share, workload, contact.
- If the fetch fails (login wall, 404, timeout), the offer stays in the running with the
  newsletter data and is marked *incomplete*. Not a knockout.

## Configuration

Already present in `config/local/application.yaml`:

```yaml
enrichment:
  enabled: true
  after: hard_filter
  fetch:
    timeout: PT10S
    rate_limit_per_minute: 20
    user_agent: "lead-generation/0.1"
    cache_ttl: P7D
    respect_robots_txt: true
  extract:
    strategy: readability
    fields: [rate, duration, start_date, remote_percent, workload, contact, full_text]
```

And in `config/local/matching-rules.yaml`:

```yaml
  rate:
    min_hourly_eur: 60
    accept_unknown: true
    apply_after: enrichment
```

## Further changes from the same analysis

1. **Extraction without a language model.** The JobScout source is set to
   `fallback: none`. The CSS selectors cover every field. The "Normalize: free text →
   structured Offer via LLM schema" stage now only applies to sources without usable
   structure, such as direct enquiries.

2. **Dedupe pulled forward.** In the concept, deduplication was step 9 of the order of
   work, on the assumption that it only pays off across several sources. That is refuted:
   12.3 % duplicates within the newsletter alone, one project up to eight times. It
   belongs right behind extraction.

3. **Normalize titles.** The `<mark>` markup around search terms and the `(m/w/d)`,
   `(w/m/d)`, `(m/f/d)` suffixes have to go before any comparison.

4. **Defuse the links.** Newsletter links go through
   `jobs.jobscout.dev/proxy?target=…&email=…`. The `target` parameter is unwrapped and the
   `email` parameter discarded — the own address does not belong in the archive, and
   certainly not in a public repository.
