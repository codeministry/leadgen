<img src="../brand/leadgen.png" alt="LEADgen / AI" height="28">

# Order of work

The sixteen steps this tool was built in, each with what it had to prove before the next one
started.

These are working notes moved out of `CLAUDE.md` so the always-loaded file stays small.
Every paragraph here was paid for once; none of it is a summary.

## Order of work

1. ✅ **Monorepo skeleton** — root build, `backend/` skeleton, `frontend/` skeleton,
   `docker-compose.yml` (postgres, api, web), `.env` loading, Flyway. `GET /api/v1/status`
   plus the `StatusStore` exist only to prove the full path (component → proxy → Spring
   → Postgres) end to end; they are not a feature.
2. ✅ **Configuration layer** — load, validate and hot-reload `sources.yaml`,
   `matching-rules.yaml`, `application.yaml`. First, because everything else stands on it.
   `ConfigRegistry.snapshot()` is how the rest of the code reads configuration.
3. ✅ **Ingest + extract** against the `local-eml` source (files, no mailbox needed).
   Acceptance test: 1289 offers from `docs/samples/emails/`, field coverage as in the analysis.
4. ✅ **IMAP connector** — same extraction, different source. Progress **never** via
   seen/unseen: the owner reads the same mails on a phone. It began as a `UIDVALIDITY`/`UID`
   watermark and is now Spring Integration's user flag; the three guarantees that cost is
   named under § *Ingest and extraction* in `docs/decisions/pipeline-ingest.md`.
5. ✅ **Dedupe** — `DeduplicationService` clusters after every ingest run, globally rather
   than per source, because the whole point is one project reaching the pipeline through
   several portals. One SQL statement, idempotent by construction.
6. ✅ **Hard filter** — six stages, every list from configuration or the profile, no
   model and no network. Reproduces `docs/samples/simulate_filter.py` exactly, and the
   corpus test asserts it against the baseline that script writes rather than against
   numbers anybody keeps in step by hand.
7. ✅ **Enrichment** — HTTP fetch of the original ad, rate limit, cache, `robots.txt`.
   A failed fetch is not a knockout; the offer stays in as *incomplete*.
8. ✅ **Scoring + digest** — deterministic factors plus a model for the four that need
   judgement, and a digest written to a file at the end of every run.
9. ✅ **Packaging** — cover letter from a Freemarker template plus the reference projects
   the offer's own skills selected, the fixed PDF for the ad's language, the archived
   original and a `meta.json`. A folder on disk; nothing is sent.
10. ✅ **Frontend** — design system, shell and all six screens, every one of them on a
    real endpoint. `GET /api/v1/offers` and `/api/v1/offers/{id}` carry the shortlist and the
    detail, `/api/v1/offers/funnel` the filter counts, `/api/v1/sources` and `/api/v1/rules` the
    configuration as the screens read it. `core/fixtures/` is gone.
11. ✅ **Manual status capture** — the `application` table and its event log,
    `GET/PATCH /api/v1/applications`, and both screens on it: the board groups by the lanes
    the endpoint states, and the offer detail carries the same control plus the dates,
    the note and the history. The dashboard's follow-up tile counts what the server
    called due. The tool never sends — it finds, filters, scores and packages; the
    operator sends the mail and therefore records the outcome by hand.
12. ✅ **Manual entry** — an offer found by hand must be able to enter the pipeline, or the
    shortlist quietly stops being the whole picture. A Markdown file uploaded in the browser
    lands in `<config-dir>/inbox/` and is read by a `manual-inbox` **file** source on the next
    run, so no new connector is needed. *(The drop zone was on the Sources screen when this was
    written and moved to Review with step 13, where the upload is reviewed before it becomes an
    offer. `POST /api/v1/sources/manual/documents` kept its path, which is what made the two
    sentences drift apart.)* One document is one offer here,
    so it needs a `markdown-frontmatter` extraction strategy: YAML frontmatter carries the
    eight-field contract, the body is the description, `fallback: llm` covers a raw pasted
    ad. `external_id` is the unwrapped URL or a content hash, otherwise re-uploading the
    same ad makes a second offer. `ProxyLink.unwrap` still applies, and the inbox is
    gitignored. `POST /api/v1/sources/manual/documents` is the first write endpoint in this
    app and writes to disk, so it needs an extension allowlist, a size limit, a sanitised
    filename and a decision about `security.auth`, which is `none` today.

    **An upload is reviewed before it becomes an offer, and the file stays the record.**
    A pasted ad has no guaranteed frontmatter and the LLM fallback can read it wrong, so
    a bad extraction would otherwise enter the shortlist silently — the one place this
    tool cannot afford to be quietly wrong, because the shortlist is what gets trusted
    instead of the mailbox. The upload therefore lands in `inbox/pending/`, which no
    source globs; a review screen shows the extracted eight fields beside the source
    text, says whether the offer is already in the pipeline (deduplication answers that
    before the confirm, not after), and lets the fields be corrected. Confirming writes
    the corrected frontmatter back into the file and moves it to `inbox/`, where the
    `manual-inbox` source picks it up on the next run. No staging table: the file is the
    state, it is inspectable with `cat`, and a rejected upload is a file that was
    deleted rather than a row nobody will ever look at.

13. ✅ **Split views** — reading an offer used to cost the list. The shortlist, the board and the review queue each keep
    their list on the left and open what is selected on the right, in two independently scrolling columns under a screen
    bounded to the viewport. The shortlist and the board open a child route on a real component; the review holds the
    file name in a query parameter, because its document is already in the store the screen reads. `/offers/:id`
    redirects into the shortlist's split view, so there is one detail view in the code. What that cost and what it
    enforces is in `docs/decisions/frontend-split-views.md`.

14. ✅ **Content segmentation** — an advert fetched from a portal carries the portal with it, and the tag cloud in it was
    being counted as skill overlap. `full_text` is split into Markdown blocks; a block is decided by a configured
    pattern, by what a digest of it was decided to be for an earlier offer, or by a model — in that order, and by nobody
    at all as the safe default. Scoring reads what is left, the detail hides the rest behind a line that says how much
    and of what kind, and re-opens it in place. What that costs and what it enforces is in
    `docs/decisions/pipeline-enrich-content.md`.

15. ✅ **Start, duration and deadline** — the three facts a person sorts adverts by, read out
    of the cleaned advert by a model instead of by three regexes that fail silently. Each is
    stored as a pair: the phrase the advert used and a normalised value a sort key can
    compare. The shortlist gained four sort keys and three filters on the back of it, with
    the keyset intact — the cursor now names its own sort, because the order decides what its
    leading value means. What that costs and what it enforces is in
    `docs/decisions/pipeline-enrich-content.md` and `docs/decisions/read-side.md`.

16. ✅ **The shortlist's filters** — ten controls in four equal rows, at one visual weight,
    spending two thirds of a card to display nothing. Three kinds of control got three
    treatments, the order became six named keys behind a trigger of its own (`duration-asc`
    and `fresh` are the two new ones, and the sentinel moved onto the sort constant to make
    the reverse possible at all), and four facets moved into a popover that shows as removable
    chips. The filter gained what it could not express: several portals at once, a free score
    range, "unscored only", and saved views. What that costs and what it enforces is in
    `docs/decisions/frontend-split-views.md` and `docs/decisions/read-side.md`.
