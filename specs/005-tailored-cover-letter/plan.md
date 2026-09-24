---
spec: 005-tailored-cover-letter
type: feature
status: draft
updated: 2026-09-24
---

# Plan 005 — The cover letter is written against the ad

**Purpose:** how the claims in `spec.md` get built. The what lives there; this file holds no
acceptance criterion.

## Approach

Four slices, in the order the claims depend on each other, with the model arriving only after the
parts that must work without it:

1. **The guard and its configuration, without a model.** `cover-letter.yaml` becomes the fifth file
   `ConfigLoader` reads, and `CoverLetterGuard` checks a draft against the profile, the ad, the chosen
   projects, the banned phrases and the word limit. Plain Java, tested with literal drafts.
2. **The writer inside the PACKAGED build.** `CoverLetterWriter` asks the configured writing model for
   a structured draft, the guard accepts or rejects it, and `PackagingService` falls back to the
   existing template on any miss. The letter's text and author are stored on `offer`, next to
   `package_dir`, by a new migration.
3. **The letter in the offer detail view.** Read, save and regenerate endpoints under the offer, and a
   letter section in the application panel.
4. **Choosing the model.** A terminal script runs the writer's prompt against real packaged offers for
   each candidate model; the operator ranks the letters blind, and the winner is set in `.env`. The
   documentation claim closes with it.

Why this order and not model-first: the one thing that makes a model-written letter safe to send is
the guard, and the guard is the part that is cheapest to get exactly right in isolation. Building it
first also means the prompt is written against a checker that already exists, so a prompt change is
measured by the rejection rate rather than by reading letters. The obvious alternative, prototyping
the prompt in a script first, was offered and not chosen: it would have held the guard's logic twice,
once in TypeScript and once in Java, until slice 1 landed.

Why the model is called during the build and not in a separate worker: the build is already
asynchronous, after the status write commits (`PackageWorker`), so a slow local model delays the
folder, not the request. A second worker would give the letter its own lifecycle, and the invariant
that PACKAGED and "there is a folder" are the same fact would have to be defended against it.

## Affected Files and Modules

| Path | Change | Claim |
|------|--------|-------|
| `backend/src/main/resources/leadgen/cover-letter.yaml` | new: neutral style rules per language (banned phrases, word limit, structure notes), `examples: {}` | ISC-258 |
| `backend/.../config/model/CoverLetterStyle.java` | new record for that file | ISC-258 |
| `backend/.../config/ConfigLoader.java`, `ConfigSnapshot.java` | fifth file, loaded over both layers; `STYLE_FILE` constant; startup banner names its layer | ISC-258 |
| `backend/.../packaging/CoverLetterGuard.java` | new: skill check against profile names and aliases of every tier and the folded haystack; project check against the chosen `ProjectView`s; banned phrases; word limit; returns a reason on rejection | ISC-252, ISC-253, ISC-254 |
| `backend/.../packaging/AdText.java` | new, package-private: `haystack()` and its folding moved out of `PackagingService`, shared by the build and the guard | ISC-252 |
| `backend/.../packaging/CoverLetterService.java` | new: read (row, else the file of a pre-V29 package), save (file, then row), draft for one offer; the SENT refusal | ISC-259, ISC-260 |
| `backend/.../packaging/CoverLetterWriter.java` | new, modelled on `ask/AdvertAsker.java`: `INSTRUCTIONS` text block in English, the ad, `contact`, `agency`, profile skills, chosen projects, `startsOnText`, style rules and examples in the user message; JSON answer (`salutation`, `body`, `skills[]`, `projects[]`) read through `Answers.objectIn`; one `LlmBudget.take()` | ISC-250, ISC-251, ISC-256 |
| `backend/.../packaging/PackagingService.java` | `build()` tries writer then guard, falls back to `render()` of the `.ftl`; an `edited` letter is written from the stored text and never redrafted; `writeMeta` records `cover_letter.author`; `haystack()` shared with the guard | ISC-250, ISC-255, ISC-257, ISC-261 |
| `backend/.../packaging/PackageArchiveService.java` | `discard` nulls the three letter columns in the same `UPDATE` that nulls `package_dir` | ISC-262 |
| `backend/src/main/resources/db/migration/V29__cover_letter.sql` | new: `offer.cover_letter_text`, `cover_letter_author`, `cover_letter_at` | ISC-257, ISC-259 |
| `backend/.../web/CoverLetterController.java` | new: `GET`/`PUT /api/v1/offers/{id}/cover-letter`, `POST …/cover-letter/draft`; 409 once the application is SENT | ISC-259, ISC-260 |
| `backend/.../web/PromptView.java` | the writing prompt joins the three shown in the UI | ISC-250 |
| `backend/src/main/resources/leadgen/pipeline.yaml` | `writing` loses "not read yet" and says what reads it | ISC-263 |
| `docs/decisions/pipeline-scoring.md` | the "only key nothing reads" paragraph and § The application package: model first, template as fallback, the guard | ISC-263 |
| `docs/CONFIGURATION.md`, `docs/DATA-MODEL.md` | the fifth file; the three columns | ISC-258, ISC-257 |
| `frontend/src/app/core/api/cover-letter.api.ts` | new seam for the three calls | ISC-259, ISC-260 |
| `frontend/src/app/core/store/cover-letter.store.ts` | new `@ngrx/signals` store, events dialect | ISC-259, ISC-260 |
| `frontend/.../offer-detail/application-panel/cover-letter/` | new component: textarea, Save, Regenerate, author badge; shown when the application is PACKAGED or later, read-only at SENT | ISC-259, ISC-260 |
| `frontend/public/i18n/en.json` | the section's strings | ISC-259 |
| `docs/samples/measure_cover_letters.ts` | new bake-off script; candidate models are CLI arguments, nothing is committed with a name | ISC-264 |
| `config/cover-letter.yaml` (gitignored) | the operator's three example letters and banned phrases | ISC-264 |

`LeadGenRuntimeHints` needs no change: `leadgen/*.yaml` already covers the new file, which is what
ISC-258 has `LeadGenRuntimeHintsTest` confirm.

## Data Model

`offer` gains three nullable columns beside `package_dir` and `packaged_at`:

| Column | Type | Written by |
|--------|------|------------|
| `cover_letter_text` | `TEXT` | the build (model or template), a save |
| `cover_letter_author` | `TEXT` with a check `IN ('model','template','edited')` | the same |
| `cover_letter_at` | `TIMESTAMPTZ` | the same |

The file in the package folder stays what the zip serves; the row is the copy the UI reads and what a
rebuild uses to keep an edit. Both are written in one method, file first, row second, so a crash
leaves the file newer and the next save or rebuild rewrites the row.

## Interfaces

| Endpoint | Body | Answers | Caller |
|----------|------|---------|--------|
| `GET /api/v1/offers/{id}/cover-letter` | — | `{text, author, at}`, 404 without a package | cover-letter store |
| `PUT /api/v1/offers/{id}/cover-letter` | `{text}` | the same shape, author `edited`; 409 at SENT | cover-letter store |
| `POST /api/v1/offers/{id}/cover-letter/draft` | — | the same shape, author `model` or `template`; 409 at SENT; 429 when the budget is spent | cover-letter store |

The draft call is synchronous, like `POST /offers/{id}/fetch`: one model call for one offer, with the
toast layer from spec 002 reporting the outcome. `meta.json` gains `cover_letter: {author}`.

## Migration and Rollback

Expand only. V29 adds nullable columns and touches no existing row; every packaged offer reads as a
letter with no stored copy, and the UI shows the file's text for those, read through the same
endpoint. No contract step follows: the template stays, and nothing is dropped.

Rollback: redeploy the previous image. Flyway on the older release sees an unknown applied V29 and
refuses to start unless `spring.flyway.ignore-migration-patterns` covers it; the concrete step is
`ALTER TABLE offer DROP COLUMN cover_letter_text, DROP COLUMN cover_letter_author, DROP COLUMN cover_letter_at; DELETE FROM flyway_schema_history WHERE version = '29';`
run as direct SQL (production maintenance never touches the codebase). What it costs: every edited
letter's row copy. The edited files in the package folders survive.

## Risks

| Risk | Blast radius | Early warning | Mitigation |
|------|--------------|---------------|------------|
| The local model drafts mostly rejected letters | every package falls back to the template, the feature is invisible | the bake-off's rejection rate per model | the guard's reason is logged per rejection; the prompt is tuned against it before the model is set |
| A slow model delays the folder by minutes | the operator waits for the download | `PackageReport` timings, the worker log | the writer's call uses the configured timeout; on timeout the template writes |
| The deployed instance's config overrides the defaults file by file | none for a new file: without an override the classpath default loads | the startup banner names the layer | the operator copies `config/cover-letter.yaml` to the instance's config dir by hand; no chart change, per the memory on chart overrides |
| The model copies an example letter's facts into an unrelated offer | a letter claiming the wrong client or history | the guard's project check, the operator's read | examples are framed in the prompt as tone only; projects come only from the ranking |
| A rebuild overwrites an edit | an edited letter lost | ISC-261's test | the build reads `cover_letter_author` before drafting |

## Conformance Impact

- G-FE-02 i18n parity (grandfathered): leave. The new strings go into `en.json` only, like every
  string since the baseline.
- FE-TST-05 coverage ratchet (grandfathered): leave.
- BE-DB-03 migration immutability (cleared): unaffected, V29 is new.
