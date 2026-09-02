# The demo

A complete, invented dataset, so a fresh clone opens on a populated application instead
of six empty screens.

```bash
cp .env.example .env    # the file has to exist; it may stay exactly as it is
docker compose -f docker-compose.yml -f docker-compose.demo.yml up --build
```

Then open <http://localhost:4200> and press **Run ingest** once.

## What is in here

| File | What it is |
|---|---|
| `generate-corpus.ts` | Writes `corpus/`. Seeded, so re-running it produces the same corpus. |
| `corpus/*.eml` | Five newsletter mails carrying ~170 invented offers. |
| `sources.yaml` | One `file` source over `corpus/`, plus the manual inbox. No mailbox, no credentials. |
| `skill-profile.yaml` | An invented freelancer, so the filter has something to filter against. |
| `matching-rules.yaml` | The shipped rules with the two lists filled in that ship empty. |

Everything is fictional. The portals are `portal-a` … `portal-f`, the agencies are
Acme, Initech, Globex and friends, and every URL points at `.example`. That is the
point: the screenshots in the README are published and the ads this tool actually reads
are not ours to publish.

## What it demonstrates

The corpus is generated against proportions measured on a real newsletter, so the
stages have something to do rather than something to explain:

- **one offer in eight is the same project through a second portal**, which is what
  deduplication collapses;
- **about one in eleven states no agency**, which is why the meta fields are addressed
  by their emoji prefix and never by position;
- **a remote share appears in under a tenth of offers and an hourly rate in about two
  per cent**, which is the entire reason the enrichment stage exists;
- offers abroad, offers out of reach, wrong roles and wrong contract forms, so every
  filter stage has a non-zero count on the funnel;
- the subject states the offer count and the extractor reads it back, which is the one
  check nothing else can make.

## The two things the demo cannot fake

**Scores need a language model.** Without `LLM_API_KEY` in `.env` the shortlist is
there, filtered and deduplicated, and every deterministic reason is written out — but
the score *total* is withheld rather than computed from five of nine weights. That is
by design: a number from half the weights is not comparable to one from all of them.
Add a key and the same run produces scored offers.

**Enrichment has nothing to fetch.** The invented URLs point at `.example`, which does
not resolve, so every offer keeps a note saying the fetch failed. Also by design: a
failed fetch is never a knockout.

## Regenerating

The corpus is committed, so it ages with the repository. `matching-rules.yaml` sets an
absurd `max_age_days` to compensate; to get mails dated to today instead:

```bash
bun demo/generate-corpus.ts          # newest mail is today, same draw
bun demo/generate-corpus.ts --seed 7 # a different draw, still reproducible
```
