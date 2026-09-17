# Documentation

Three kinds of document live here, and the difference between them is who they are written
for. Mixing them up is how a folder like this becomes unreadable, so the split is worth one
paragraph each.

The **guides** are for a person reading this repository for the first time: prose, in
reading order, each one answering a question somebody actually has. The **decision records**
in [`decisions/`](decisions/) are working notes moved out of `CLAUDE.md` — every rule with
the measurement behind it and the alternative that was not taken, written for an agent (or a
maintainer) already inside the tree. The **samples** in [`samples/`](samples/) are neither:
they are the measuring instruments and their output, which is what keeps every number in the
other two honest.

The root [`README.md`](../README.md) is the front door and stays shorter than any of this.
[`CLAUDE.md`](../CLAUDE.md) carries the invariants an agent must not break.

## Start here

| If you want to…                                     | Open                                     |
|-----------------------------------------------------|------------------------------------------|
| run the thing and hack on it                        | [DEVELOPMENT.md](DEVELOPMENT.md)         |
| understand what it does, stage by stage             | [ARCHITECTURE.md](ARCHITECTURE.md)       |
| point it at your own mailbox or portal              | [ADDING-A-SOURCE.md](ADDING-A-SOURCE.md) |
| change what survives the filter, or what scores     | [WRITING-RULES.md](WRITING-RULES.md)     |
| know where a value comes from, or why it is missing | [CONFIGURATION.md](CONFIGURATION.md)     |
| see it run without a mailbox                        | [`demo/README.md`](../demo/README.md)    |
| know *why* a stage looks the way it does            | [`decisions/`](decisions/), table below  |

## The guides

- **[ARCHITECTURE.md](ARCHITECTURE.md)** — the modular monolith, the pipeline stage by
  stage, and the reasoning behind the parts that are not obvious. The single document to
  read if you only read one.
- **[DEVELOPMENT.md](DEVELOPMENT.md)** — prerequisites with pinned versions, the commands,
  and the traps a newcomer hits first (Docker is required for the backend tests; Postgres is
  published on 55432, not 5432).
- **[CONFIGURATION.md](CONFIGURATION.md)** — the two layers, the four files, every
  environment variable, and what the startup banner is telling you.
- **[ADDING-A-SOURCE.md](ADDING-A-SOURCE.md)** — a new source is a block of YAML, worked
  through line by line against the source that ships enabled, then every key with what reads
  it. If adding a source means editing Java, that is a bug.
- **[WRITING-RULES.md](WRITING-RULES.md)** — the six knockouts, the weight table and the
  thresholds, all of it without reading any Java. Also marks the keys that are read by
  nothing.
- **[CONCEPT.md](CONCEPT.md)** — the original design: domain model, module layout, order of
  work. Historical in places; where it and `ARCHITECTURE.md` disagree, the architecture won.
- **[CONCEPT-addendum-enrichment.md](CONCEPT-addendum-enrichment.md)** — the derivation of
  the enrichment stage. Merged into the concept; kept because it records why the pipeline
  grew a stage after the fact.
- **[SAMPLE-ANALYSIS.md](SAMPLE-ANALYSIS.md)** — what 14 real newsletter mails contain and
  what they do not. Source names anonymised, every figure measured, reproducible with the
  two scripts in `samples/`. This is where the numbers quoted elsewhere come from.

## The decision records

One per area, each a set of rules with the paragraph behind each of them. The table in
[`CLAUDE.md`](../CLAUDE.md) § *The decision records* is the authoritative copy — a test
fails when a document here is missing from it — and this is the same list with a little more
room.

| Document                                                           | What it settles                                                                                    |
|--------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|
| [pipeline-ingest.md](decisions/pipeline-ingest.md)                 | Connectors, the configured extraction table, the Markdown inbox and the review in front of it      |
| [pipeline-dedupe-filter.md](decisions/pipeline-dedupe-filter.md)   | What collapses a duplicate, the six deterministic filter stages, the archive axis                  |
| [pipeline-enrich-content.md](decisions/pipeline-enrich-content.md) | The only fetch that leaves the machine, block labelling, start/duration/deadline                   |
| [pipeline-scoring.md](decisions/pipeline-scoring.md)               | Rules before model, the weight table that outranks the judge, the digest and the package folder    |
| [read-side.md](decisions/read-side.md)                             | The working-set predicate, keyset paging, the six sort keys, the filters                           |
| [configuration.md](decisions/configuration.md)                     | The two layers, the three files read as one snapshot, the startup banner                           |
| [frontend-split-views.md](decisions/frontend-split-views.md)       | The three split screens, the shell, and the first screen that writes                               |
| [frontend-design-system.md](decisions/frontend-design-system.md)   | Both themes, the accent's one meaning, the navigation, the catalogs                                |
| [manual-status.md](decisions/manual-status.md)                     | The eleven application states and their event log                                                  |
| [order-of-work.md](decisions/order-of-work.md)                     | The sixteen steps this tool was built in, and what each had to prove                               |

## samples/ — the measuring instruments

Three scripts are committed; everything they read and everything they write is gitignored,
because the corpus is real mail and real mail carries an address.

```bash
python3 docs/samples/analyze_samples.py    # extraction, field coverage, duplicates
python3 docs/samples/simulate_filter.py    # the hard filters, writes filter-baseline.json
bun docs/samples/measure_embeddings.ts <model> [dims]   # observed pair similarities
```

The two Python scripts are the **reference implementation**: whatever they do, the Java has
to reproduce, and the corpus test asserts against `filter-baseline.json` rather than against
a number written down in prose. `measure_embeddings.ts` is what a similarity threshold has to
be measured with before it is changed — the bands are a property of the model and the market,
not of the number. Its `embedding-observed-*.md` reports and `embedding-cache-*.json` stay
out of the repository for size, and the conclusions drawn from them live in
[decisions/pipeline-dedupe-filter.md](decisions/pipeline-dedupe-filter.md).

`screenshots/` holds what the root README renders; the light and dark ones are chosen per
screen there, not by theme.

## Where a new document goes

The same split the working notes use. A **rule** — one or two lines, the imperative — goes
into the `CLAUDE.md` that is loaded when somebody could break it, never here. Its **reasoning** goes into the matching
file in `decisions/`, and a new file there has to be
added to the table in `CLAUDE.md` or `WorkingNotesStaySmallTest` fails. A **guide** belongs
here at the top level, and earns its place by answering a question a reader has before they
have read the code — if it only restates the code, the code was the better place.
