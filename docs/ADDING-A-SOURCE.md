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

## Two things worth knowing before you write selectors

**Only the prose field is converted to Markdown.** `text()` joins every node with a space,
so an advert would arrive as one line. `description` (and `full_text` in enrichment) go
through `HtmlToMarkdown`, which keeps the headings, the lists and the emphasis. Every other
field stays flat text — a title in an `<h3>` would otherwise arrive as
`### Senior Java Developer`, in the shortlist, in the fingerprint and in the cover letter.

**A pattern reads a line, a field reads a document.** A regex in YAML is written against a
line, `.` does not match a newline, and `**` around a word breaks a pattern outright.

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

## Checking your work

```bash
./gradlew :backend:test --tests '*ExtractionTest*'
curl -s -X POST http://localhost:8080/api/ingest | jq '.sources'
```

The report names, per document, how many offers were extracted and how many the document
announced. Then open the **Sources** screen: it lists the configuration rather than the
database, so a source that has never run still appears — which is exactly the failure you
are looking for when a new block seems to do nothing.
