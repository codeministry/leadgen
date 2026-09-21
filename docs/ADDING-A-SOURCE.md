# Adding an offer source

A new source is a block of YAML. If you find yourself editing Java to add one, that is a
bug in the abstraction and worth reporting as one.

This walks through the whole thing against the source that ships enabled in the demo, so
every line below is one you can read in the repository.

## The two seams

The core knows exactly two things, and everything else is configuration:

```java
interface SourceConnector { List<RawDocument> read(Source source, long sourceId); }
interface ExtractionStrategy { List<ExtractedOffer> extract(RawDocument doc, Extraction config); }
// Illustrative. There is no such interface in the code: the strategies are a switch in
// IngestService.read, and a new one is a case there.
```

A **connector** is chosen by `type` and fetches documents. Two are implemented: `file`
(a directory) and `imap` (a mailbox). A **strategy** is chosen by `extraction.strategy` and
turns one document into zero or more offers. Two are implemented: `html-blocks` (one
document holds many offers, the newsletter case) and `markdown-frontmatter` (one document
is one offer, the by-hand case).

If your source is a directory of files or an IMAP folder, and its documents are HTML or
Markdown, **you need no code at all.**

## The contract you are filling in

Eight field names, and they are the contract between `sources.yaml` and `OfferMapper`:

```
title  url  description  location  portal  agency  published  tags
```

A field spelled differently is extracted and then ignored, in silence. That is the single
most common mistake here, and it is why an uploaded document is reviewed before it becomes
an offer.

## A worked example

This is `demo-newsletter` from [`demo/sources.yaml`](../demo/sources.yaml), reading five
generated mails out of a directory:

```yaml
sources:
  - id: demo-newsletter
    enabled: true
    type: file
    path: corpus            # relative to the CONFIGURATION directory, not the working one
    glob: "*.eml"
    extraction:
      strategy: html-blocks
      block_selector: "div.job-card"
      prefer_part: html
      expect_count_from_subject: "^(\\d+)"
      fields:
        title:       { css: "h3.job-title" }
        description: { css: "div.job-description" }
        url:         { css: "a.job-link", attr: href, unwrap_query_param: target }
        agency:      { css: "div.job-meta span", prefix: "🏢 " }
        location:    { css: "div.job-meta span", prefix: "📍 " }
        published:   { css: "div.job-meta span", prefix: "📅 ", format: "dd.MM.yyyy – HH:mm 'Uhr'" }
        portal:      { css: "div.job-meta span", prefix: "🔗 " }
        tags:        { ancestor: "div.tag-group", css: "h2.tag-header", list: true, split: "+" }
      fallback: none
    defaults:
      language: de
      channel: newsletter
```

### Line by line, and why

**`path` is resolved against the configuration directory.** Against the working directory
the very same configuration would point at `backend/…` under `bootRun`, at the repository
root in an IDE and at neither from a jar — three empty directories that all look like a
source with nothing in it. A path that only resolves from the working directory still
works, and logs a warning naming both.

**`prefer_part: html`, and the search runs backwards.** `multipart/alternative` orders its
parts least-preferred first, so the plain-text version comes *before* the HTML one. Taking
part zero yields text with none of the structure the selectors address, and extracts zero
offers from a perfectly intact mail.

**`expect_count_from_subject` is the one check nothing else can make.** A selector that
stops matching loses offers, and fewer offers is indistinguishable from a quiet day on the
market. The document states its own count; a mismatch is logged loudly and never discards
what did come through. Use it whenever the document announces a number.

**Meta fields are addressed by their emoji prefix, never by position.** Four spans sit in
one row, and in the measured corpus 9.2 % of offers state no company — read by position,
every following field of those offers is shifted by one.

**`format` describes the whole value, not a prefix of it.** Cutting the raw string to the
pattern's length works only while the two happen to line up, and stops at the first quoted
literal. The time is parsed and then dropped: it carries no zone, and the freshness rule
counts days.

**`unwrap_query_param: target` is a privacy boundary, not a convenience.** Every link in a
newsletter carries the subscriber's mail address as a query parameter. `ProxyLink.unwrap`
keeps the target and discards the rest of the query; an unrecognised wrapper loses its
whole query rather than keeping it. There is a test that fails on an `@`, an `email=` or a
`%40` in any extracted URL.

**`tags` come from the block's ancestor.** The search that found the offer is the group
heading it sits under, not anything inside the card.

**`fallback: none` means no language model is involved.** Set it only when every field
really does come out of the markup — and then prove it with `expect_count_from_subject`.

## Four things worth knowing before you write selectors

**Only the prose field is converted to Markdown.** `text()` joins every node with a space,
so an advert would arrive as one line. `description` (and `full_text` in enrichment) go
through `HtmlToMarkdown`, which keeps the headings, the lists and the emphasis. Every other
field stays flat text — a title in an `<h3>` would otherwise arrive as
`### Senior Java Developer`, in the shortlist, in the fingerprint and in the cover letter.

**A pattern reads a line, a field reads a document.** A regex in YAML is written against a
line, `.` does not match a newline, and `**` around a word breaks a pattern outright.

**A marketing mail has no classes, so select on the shape of a link — with a child
combinator.** Table layouts from the usual mail builders carry nothing semantic, and the only
stable feature of a card is that the project link in it looks like a project link.
`table:has(a[href*=/projects/])` is the obvious way to say that and the wrong one: `:has()`
matches every *ancestor* table as well, so one card is counted once per nesting level.
Measured on three real mails: 46, 56 and 31 blocks where there were 9, 11 and 6. Anchor the
path instead — `td:has(> p > a[href*=/projects/])` — and pin the count in a test, because a
block that yields no title is dropped without a word.

**`prefix` returns before everything else, so it cannot be combined.** It filters the matched
elements on `text()` and hands back the first one with the prefix stripped — `attr`, `regex`,
`unwrap_query_param`, `list` and `split` are never reached. That matters when one element
carries several labels (`Place: … // Contract: … // Start: …`): a prefix read gives you all
three. Narrow the selector until it reaches that one element, then use `regex` with a group.

## Inheriting instead of copying

A second source that reads the same kind of document points at the first:

```yaml
  - id: local-eml
    type: file
    path: ${INBOX_DIR:./data/inbox}
    glob: "*.eml,*.html"
    extraction:
      inherit: sample-newsletter
```

It resolves at load, one level only. Two copies of a selector table drift, and the copy
nobody looks at drifts unnoticed.

## Credentials

They never appear in `sources.yaml`. A `connections` entry names environment variables:

```yaml
connections:
  - id: mailbox-primary
    type: imap
    host: ${IMAP_HOST}
    port: ${IMAP_PORT:993}
    ssl: true
    username: ${IMAP_USER}
    password: ${IMAP_PASSWORD}
```

The values live in `.env`, which is gitignored. `${VAR}` without a value becomes the empty
string, and an empty YAML scalar is **null**, not `""` — whether that is acceptable is a
question about the field, so validation answers it: an unset LLM key is fine, an unset IMAP
host on an *enabled* source is not.

The `connections` block may be omitted entirely when every source is a file source.

## If you do need code

You need a new `SourceConnector` only for a genuinely new transport (an HTTP feed, a
webhook), and a new `ExtractionStrategy` only for a genuinely new document shape (JSON,
plain-text prose). Both are Spring components chosen by a string from the configuration;
`FileSourceConnector` and `MarkdownExtractor` are the smallest examples of each.

Three rules the existing implementations follow and a new one has to:

- **The cursor advances after the write, never after the read.** `SourceConnector.commit`
  exists for exactly that. A cursor moved at read time plus a failure afterwards means
  those documents are never looked at again, and nothing says so.
- **One failing source must not end the run.** `IngestService` catches per source.
- **Progress is never tracked by seen/unseen.** The same mailbox is read on a phone.

## Every key, and what reads it

The worked example above is one shape of one source. This is the whole schema, and a
`read by` column saying `nothing` where that is the truth — the shipped file declares more
than the code reads, and there is no way to tell from the file itself.

### What is actually implemented

| `type` | Connector | Fetches |
|---|---|---|
| `file` | `FileSourceConnector` | every file in a directory matching `glob` |
| `imap` | `ImapSourceConnector` | messages of a folder matching `selector` |

| `extraction.strategy` | Extractor | Shape |
|---|---|---|
| `html-blocks` | `HtmlBlockExtractor` | one document holds many offers |
| `markdown-frontmatter` | `MarkdownExtractor` | one document is one offer |

Anything else is **logged and skipped, not fatal** — a `type` with no connector says so
once per run and the source contributes nothing. That matters because the shipped file
names two that do not exist: `sample-portal-feed` declares `type: rss` and
`sample-direct-enquiry` declares `strategy: llm`. Both ship disabled.

### `connections[]`

Omit the block entirely when every source reads files; a missing `connections:` is an empty
one, not an error.

| Key | Type | Required | Read by |
|---|---|---|---|
| `id` | string | yes | referenced by `source.connection` |
| `type` | string | yes | `imap` is the only kind with a connector |
| `host`, `port`, `ssl`, `username`, `password` | | | the IMAP connector |
| `mode` | string | | nothing |
| `poll_interval` | duration | | nothing |

One cross-file check is fatal: an **enabled** source pointing at an `imap` connection whose
host, username or password is blank fails at load, naming `IMAP_HOST`, `IMAP_USER` and
`IMAP_PASSWORD`. A disabled source with the same gap is fine, which is what lets the shipped
defaults carry a mailbox nobody has configured.

### `sources[]`

| Key | Type | Read by |
|---|---|---|
| `id` | string, required | the offer's `source_id`, and the Sources screen |
| `enabled` | bool, default `false` | the run |
| `type` | string, required | connector lookup |
| `connection` | string | `imap` only; must name a declared connection |
| `path` | string | `file` only — **resolved against the configuration directory** |
| `glob` | string | `file` only, comma-separated |
| `selector` | block | `imap` only |
| `extraction` | block, required | the extractor |
| `url` | string | nothing |
| `schedule` | duration | nothing — a run is triggered, not scheduled per source |
| `defaults` | map | nothing |

### `selector` — `imap` only

| Key | Type | Read by |
|---|---|---|
| `folder` | string | the mailbox to open. **Blank fails the run for that source.** |
| `from` | list | both the server-side IMAP `SEARCH` and the local re-check |
| `exclude_from` | list | the local re-check |
| `subject_matches` | regex | the local re-check |
| `since_days` | int | the local re-check |
| `match_all` | bool | the local re-check, where it takes every message that got past `since_days` and `exclude_from` |
| `mark_seen` | bool | nothing |
| `state` | string | nothing — the UID cursor it documents was removed |

`match_all: true` is the dedicated-folder case: the folder holds nothing but this
newsletter, so no sender or subject filter is needed. It short-circuits `subject_matches`
but **not** `since_days` or `exclude_from`, which still apply.

**It does not short-circuit `from`, and cannot.** The senders are in the server-side
`SEARCH` as well, and that is what lets several sources share one folder without marking
each other's mail as taken. A selector that sets both therefore reads as "take everything"
and behaves as a sender filter, so the load refuses the combination rather than picking one.

Three arrangements are refused when the configuration is read, each because its failure is
otherwise silent:

| Refused | Why |
|---|---|
| `match_all: true` beside `from` | the two say opposite things and the sender wins |
| an `imap` selector with no `from`, no `subject_matches` and no `match_all` | it reads the whole folder, which is dedicated mode by accident and looks identical to the deliberate kind until a second sort of mail lands there |
| `match_all: true` in a folder another **enabled** source also reads | the dedicated source marks that source's mail as taken before it runs, and the run reports zero with no error |

### `extraction`

| Key | Type | Read by |
|---|---|---|
| `strategy` | string | required **after** inheritance is resolved |
| `block_selector` | CSS | `html-blocks` |
| `fields` | map | both extractors |
| `inherit` | source id | the loader |
| `prefer_part` | string, default `html` | the MIME part search |
| `date_format` | pattern | the fallback for a field without its own `format` |
| `expect_count_from_subject` | regex | the count check |
| `fallback` | `none` \| `llm` | **`markdown-frontmatter` only** |

Three behaviours that are not obvious from the key names:

**`glob` is suffix matching, not globbing.** A leading `*` is stripped and the rest is a
suffix test, so `*.eml` works and `report-*.eml` matches nothing at all. An empty or absent
`glob` takes every file.

**`inherit` replaces the whole extraction block.** A child cannot override one field of its
parent — it states `inherit` and gets the parent's table verbatim. One level only: a parent
that itself inherits is rejected with *"…which inherits itself — one level only"*, and a
parent that is not declared with *"…which is not declared"*.

**`fallback` is read only by `MarkdownExtractor`, and only when a document has no
frontmatter at all.** On an `html-blocks` source the key is inert, which is worth knowing
before you trust it: `fallback: none` there is documentation, not a guarantee. `llm` hands
the document to the model named by `llm.models.extraction` (falling back to
`llm.models.scoring`); an unrecognised value warns, names the two implemented ones, and
behaves as `none`.

### `extraction.fields.<name>`

The eight names that mean anything are the ones listed under *The contract you are filling
in* above. Each maps to a block of these keys:

| Key | What it does |
|---|---|
| `ancestor` | look outside the block, at the nearest matching ancestor |
| `css` | the element inside the scope; absent means the scope itself |
| `prefix` | pick the one matched element whose text starts with this, and strip it |
| `list` + `split` | take every match, split each on a literal separator |
| `attr` | take an attribute instead of the text |
| `unwrap_query_param` | the link is a tracking proxy; keep this parameter, discard the query |
| `regex` | group 1 if the pattern has one, otherwise the whole match |
| `format` | how to read a date out of **this** field, overriding `date_format` |
| `path`, `html` | nothing reads them |

**The order matters, because getting it wrong produces a null rather than an error:**

1. `ancestor` — no matching ancestor and the field is null, immediately.
2. `css` — no match and the field is null.
3. **`prefix` returns here.** It filters the matches on their text, strips the prefix and
   hands back the first one. `attr`, `regex`, `unwrap_query_param`, `list` and `split` are
   **never reached**. When one element carries several labels, narrow the selector until it
   reaches that one element and use `regex` with a group instead.
4. `list` + `split` — all matches, each split, trimmed, empties dropped.
5. Otherwise the first match: `attr` (or its text) → `unwrap_query_param` → `regex`.

A field whose name is not one of the eight is extracted and then dropped in silence, and a
block whose `title` comes out blank is dropped with it.

## Checking your work

```bash
./gradlew :backend:test --tests '*ExtractionTest*'
curl -s -X POST http://localhost:8080/api/v1/ingest | jq '.sources'
```

The report names, per document, how many offers were extracted and how many the document
announced. Then open the **Sources** screen: it lists the configuration rather than the
database, so a source that has never run still appears — which is exactly the failure you
are looking for when a new block seems to do nothing.
