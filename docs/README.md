<img src="brand/leadgen.png" alt="LEADgen / AI" height="28">

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
| know which table a stage writes, and how they relate | [DATA-MODEL.md](DATA-MODEL.md)          |
| follow a run, a batch, or a write from the endpoint down | [BACKEND-FLOWS.md](BACKEND-FLOWS.md) |
| see where a rule decides and where a model speaks   | [BACKEND-FLOWS.md § 1e](BACKEND-FLOWS.md#1e-where-a-rule-decides-and-where-a-model-speaks) |
| know what the vectors hold, and what they may not decide | [EMBEDDINGS.md](EMBEDDINGS.md)      |
| point it at your own mailbox or portal              | [ADDING-A-SOURCE.md](ADDING-A-SOURCE.md) |
| change what survives the filter, or what scores     | [WRITING-RULES.md](WRITING-RULES.md)     |
| know where a value comes from, or why it is missing | [CONFIGURATION.md](CONFIGURATION.md)     |
| see it run without a mailbox                        | [`demo/README.md`](../demo/README.md)    |
| know *why* a stage looks the way it does            | [`decisions/`](decisions/), table below  |
| build or debug the native image                     | [native-image.md](decisions/native-image.md) |

## The guides

- **[ARCHITECTURE.md](ARCHITECTURE.md)** — the modular monolith, the pipeline stage by
  stage, and the reasoning behind the parts that are not obvious. The single document to
  read if you only read one.
- **[DATA-MODEL.md](DATA-MODEL.md)** — the thirteen tables as Flyway builds them: one ER
  diagram, `offer` grouped by the stage that owns each column, every other table with its
  writers and readers, and the legal values of every status-like column. Derived from the
  migrations; when the two disagree, the migration wins and this file is fixed.
- **[BACKEND-FLOWS.md](BACKEND-FLOWS.md)** — what happens, in order, when work starts: the
  entry points, the run as a sequence with what each stage reads and writes, where a rule
  decides and where a model speaks, the batch collector and the package worker, every write
  path from an endpoint down to a table, and the four state machines. Entry points are named
  by file and method so a breakpoint can go where the text says.
- **[EMBEDDINGS.md](EMBEDDINGS.md)** — the two vector columns and the three vectors kept in
  memory: what text each one holds and which stage writes it, the path from text through the
  width check into pgvector and its index, the band that merges or flags a duplicate, how the
  search narrows without ranking and how the letter picks its reference projects, which
  numbers are thresholds and what measures them, what a vector may never decide, and what
  every reader does without an embedding model.
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
- **[CONCEPT.md](CONCEPT.md)** — the original design: module layout and order of work.
  Historical in places; where it and `ARCHITECTURE.md` disagree, the architecture won, and
  its domain model was replaced by `DATA-MODEL.md`.
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
| [read-side.md](decisions/read-side.md)                             | The working-set predicate, keyset paging, the ten sort keys, the filters                           |
| [retrieval.md](decisions/retrieval.md)                             | What a vector may decide and what it may not; built, and walked through in [EMBEDDINGS.md](EMBEDDINGS.md) |
| [configuration.md](decisions/configuration.md)                     | The two layers, the three files read as one snapshot, the startup banner                           |
| [frontend-split-views.md](decisions/frontend-split-views.md)       | The three split screens, the shell, and the first screen that writes                               |
| [frontend-design-system.md](decisions/frontend-design-system.md)   | Both themes, the accent's one meaning, the navigation, the catalogs                                |
| [manual-status.md](decisions/manual-status.md)                     | The eleven application states and their event log                                                  |
| [order-of-work.md](decisions/order-of-work.md)                     | The sixteen steps this tool was built in, and what each had to prove                               |
| [native-image.md](decisions/native-image.md)                       | Why there are two images, the AOT cache's training problem, and the hints written by hand          |

## samples/ — the measuring instruments

The scripts are committed; everything they read and everything they write is gitignored,
because the corpus is real mail and real mail carries an address.

```bash
python3 docs/samples/analyze_samples.py    # extraction, field coverage, duplicates
python3 docs/samples/simulate_filter.py    # the hard filters, writes filter-baseline.json
bun docs/samples/measure_embeddings.ts <model> [dims]   # observed pair similarities
bun docs/samples/measure_routing.ts --models=<a>,<b> <ids…>   # a smaller model per bounded question
```

The two Python scripts are the **reference implementation**: whatever they do, the Java has
to reproduce, and the corpus test asserts against `filter-baseline.json` rather than against
a number written down in prose. `measure_embeddings.ts` is what a similarity threshold has to
be measured with before it is changed — the bands are a property of the model and the market,
not of the number. Its `embedding-observed-*.md` reports and `embedding-cache-*.json` stay
out of the repository for size, and the conclusions drawn from them live in
[decisions/retrieval.md](decisions/retrieval.md) and
[decisions/pipeline-dedupe-filter.md](decisions/pipeline-dedupe-filter.md); which number each
script guards is [EMBEDDINGS.md § 6](EMBEDDINGS.md#6-thresholds-and-how-they-are-measured).
`measure_routing.ts` is what `llm.models.content` and `llm.models.fields` are chosen with: it asks
each candidate the pipeline's own bounded questions through `POST /api/v1/offers/{id}/answer` and
prints its agreement with the stored answers beside an empty-answer baseline; the reasoning is in
[decisions/pipeline-scoring.md](decisions/pipeline-scoring.md#measuring-a-candidate).

`screenshots/` holds what the root README renders; the light and dark ones are chosen per
screen there, not by theme.

## Conventions in the guides

The guides are written for GitHub's renderer, and every one of them carries at least one
diagram. A new guide, or a section added to an existing one, keeps to the same five rules
so the folder reads as one.

- **Diagrams are Mermaid, in a fenced block**, never an image of a diagram: they diff, and a
  reviewer can fix a wrong edge in the pull request. Keep to what GitHub renders: one-token
  attribute types in an `erDiagram`, at most seven participants in a sequence.
- **Every flowchart, sequence and state diagram opens with one `%%{init}%%` line** setting
  `themeVariables` for its boxed elements only: nodes, clusters, actors, notes and the
  `alt`/`loop` tags get a light fill with dark text, so a viewer's dark theme cannot hand a
  tile a dark background with dark text. Lines and the text drawn straight on the canvas
  are deliberately not set, because they have to follow the theme to stay readable on a
  dark page. A `rect` in a sequence diagram is an `rgba(...)` tint for the same reason. An
  `erDiagram` sets nothing; its boxes and their text both follow the theme.
- **One colour per meaning, across every diagram.** Grey-blue is deterministic and free,
  violet asks a model, green leaves the machine, peach writes a file; a dashed white box is a
  database row. State diagrams use the board's five lane colours instead. Declared once per
  diagram with `classDef` (or a `rect` block in a sequence), as mid-light fills with dark
  text, so they read on both of GitHub's themes.
- **Callouts carry invariants and traps only.** `[!NOTE]` for scope, `[!IMPORTANT]` for a
  repository invariant, `[!WARNING]` for a trap that has cost something, `[!TIP]` for a
  shortcut. Never for ordinary prose.
- **`<details>` hides what a reader scans past**: full column lists, long tables. The summary
  line says what is inside and how much. It is the one piece of HTML these documents use.
- **Screenshots come from `screenshots/`** and are captured from the demo stack, never from a
  real corpus. A new one is added only when a flow ends on a screen no existing image shows.

To check the diagrams before a push, `mmdc` (the Mermaid CLI, `brew install mermaid-cli`)
renders every block of a Markdown file and fails on the first one that does not parse:

```bash
mmdc -p puppeteer.json -i docs/BACKEND-FLOWS.md -o /tmp/out.md    # one SVG per block
```

Homebrew's build finds no browser of its own, so `puppeteer.json` names one:
`{"executablePath": "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}`.
Render once with `-t default` and once with `-t dark` and open the SVGs: a diagram that
parses but cannot be read is still a defect.

## Where a new document goes

The same split the working notes use. A **rule** — one or two lines, the imperative — goes
into the `CLAUDE.md` that is loaded when somebody could break it, never here. Its **reasoning** goes into the matching
file in `decisions/`, and a new file there has to be
added to the table in `CLAUDE.md` or `WorkingNotesStaySmallTest` fails. A **guide** belongs
here at the top level, and earns its place by answering a question a reader has before they
have read the code — if it only restates the code, the code was the better place.
