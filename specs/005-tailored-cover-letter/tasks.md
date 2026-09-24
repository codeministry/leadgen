---
spec: 005-tailored-cover-letter
plan: plan.md
updated: 2026-09-24
---

# Tasks 005 — The cover letter is written against the ad

**Purpose:** atomic, checkable steps. Each task hangs on exactly one claim ID from
`spec.md`. This file defines nothing, it decomposes.

## Legend

`[P]` = parallelizable. `(after: T…)` = must run after that task. `· <lane>` = derived from the path column:
the constitution has no `## Lanes` block, so the defaults apply, with `server` for `backend/`, `web` for
`frontend/` and `docs` for `docs/`. Backend tests sit in `server` beside the code they probe. `operator` marks
the steps only the operator can take: a gitignored file and a judgement. `[seam]` = the contract between two
lanes; nothing across it runs before it.

`[P]` was derived from `IsaFrontier.ts frontier` on 2026-09-24, after the guard claims lost their edge to
ISC-250 (they are checked on literal drafts and need no writer). Takeable: ISC-250, 252, 253, 254, 256, 258,
262, 263, 264. Four tasks carry `[P]`, T1, T4, T11 and T39, one file set each. Every other task on a takeable
claim follows one of them, or shares `CoverLetterGuard.java`, `PackagingService.java` or
`PackagingServiceTest.java`, which serialises most of the server lane. That is the price of the build method
being one method. The seam is T26, on ISC-259: `plan.md` § Interfaces fixes all three endpoints and the
`{text, author, at}` shape, T26 lands the signatures, and every `web` task waits for it. The `…` prefix is
`backend/src/main/java/de/codeministry/leadgen`, and `…test` is the same under `src/test`.

## Tasks

### Slice 1 — the style file and the guard, without a model

- [x] T1 · ISC-258 · [P] · server — `CoverLetterStyle` record (per language: banned phrases, word limit, structure notes; `examples` per language) and the neutral classpath default with no example · `…/config/model/CoverLetterStyle.java`, `backend/src/main/resources/leadgen/cover-letter.yaml`
- [x] T2 · ISC-258 · server — `ConfigLoader` reads `cover-letter.yaml` as the fifth file over both layers, `ConfigSnapshot` carries it, the startup banner names its layer; callers constructing a snapshot in tests follow (after: T1) · `…/config/ConfigLoader.java`, `…/config/ConfigSnapshot.java`
- [x] T3 · ISC-258 · server — loader tests: default with zero examples, an override in the config dir wins (after: T2) · `…test/config/ConfigLoaderTest.java`
- [x] T4 · ISC-252 · [P] · server — `CoverLetterGuard` with a `Draft` record (`salutation`, `body`, `skills`, `projects`) and a verdict carrying the rejection reason; the skill check against profile names and aliases of every tier and the folded ad text · `…/packaging/CoverLetterGuard.java`
- [x] T5 · ISC-252 · server — `haystack()` and its folding move out of `PackagingService` into a package-private `AdText`, shared by the build and the guard (after: T4) · `…/packaging/AdText.java`, `…/packaging/PackagingService.java`
- [x] T6 · ISC-252 · server — guard tests: a skill missing from the profile, a profile skill missing from the ad, a draft that passes (after: T5) · `…test/packaging/CoverLetterGuardTest.java`
- [x] T7 · ISC-253 · server — the project check against the titles of the `ProjectView`s the ranking chose, in the letter's language (after: T4) · `…/packaging/CoverLetterGuard.java`
- [x] T8 · ISC-253 · server — guard test: a project the ranking did not choose (after: T6, T7) · `…test/packaging/CoverLetterGuardTest.java`
- [x] T9 · ISC-254 · server — banned phrases (case-folded, per letter language) and the word limit, both read from `CoverLetterStyle` (after: T1, T7) · `…/packaging/CoverLetterGuard.java`
- [x] T10 · ISC-254 · server — guard tests: one banned phrase, one draft over the limit (after: T8, T9) · `…test/packaging/CoverLetterGuardTest.java`

### Slice 2 — the writer inside the PACKAGED build

- [x] T11 · ISC-256 · [P] · server — `ChatModels` resolves the writing model from `llm.models.writing` alone and answers empty when it is unset; no default to another key · `…/llm/ChatModels.java`
- [x] T12 · ISC-250 · server — `CoverLetterWriter`: English `INSTRUCTIONS` text block, the user message (ad text, `contact`, `agency`, profile skills, chosen projects, `startsOnText`, style rules, examples framed as tone only), one `LlmBudget.take()`, `Answers.objectIn` into a `Draft`; empty on no model, no permit or a failed call (after: T4, T11) · `…/packaging/CoverLetterWriter.java`
- [x] T13 · ISC-251 · server — the salutation: the prompt asks for the contact's name, and the writer replaces the model's salutation with the language's neutral one when `contact` names no person (after: T12) · `…/packaging/CoverLetterWriter.java`
- [x] T14 · ISC-251 · server — writer tests for both salutations against a stub `ChatModel` (after: T13) · `…test/packaging/CoverLetterWriterTest.java`
- [x] T15 · ISC-250 · server — `PackagingService.build()`: writer, then guard, then the accepted draft as `cover_letter.txt` in the letter's language (after: T10, T12) · `…/packaging/PackagingService.java`
- [x] T16 · ISC-250 · server — build test with a stub writing model, one German and one English offer (after: T15) · `…test/packaging/PackagingServiceTest.java`
- [x] T17 · ISC-255 · server — every miss (no model, no permit, a thrown call, a rejected draft) renders the `.ftl` as today; the rejection reason is logged with the offer id (after: T16) · `…/packaging/PackagingService.java`
- [x] T18 · ISC-255 · server — build tests for the four misses (after: T17) · `…test/packaging/PackagingServiceTest.java`
- [x] T19 · ISC-256 · server — build test: a throwing writing stub beside a configured scoring model reaches no other model (after: T11, T18) · `…test/packaging/PackagingServiceTest.java`
- [x] T20 · ISC-257 · server — `V29__cover_letter.sql`: the three nullable columns on `offer` and the author check (after: T17) · `backend/src/main/resources/db/migration/V29__cover_letter.sql`
- [x] T21 · ISC-257 · server — the build stores text, author and time, file first and row second, and `writeMeta` records `cover_letter.author` (after: T20) · `…/packaging/PackagingService.java`
- [x] T22 · ISC-257 · server — build tests for `model` and `template` in `meta.json` and the row (after: T19, T21) · `…test/packaging/PackagingServiceTest.java`
- [x] T23 · ISC-262 · server — `discard` nulls the three letter columns in the `UPDATE` that nulls `package_dir` (after: T20) · `…/packaging/PackageArchiveService.java`
- [x] T24 · ISC-262 · server — archive tests: unsent archived and restored, sent archived (after: T23) · `…test/packaging/PackageArchiveServiceTest.java`
- [x] ~~T25 · ISC-250 · server — the writing prompt joins the three prompts `PromptView` shows (after: T12) · `…/web/PromptView.java`~~ — struck: not part of ISC-250 as written; moved to the master's Remaining Work

### Slice 3 — the letter in the offer detail view

- [x] T26 · ISC-259 · [seam] · server — `CoverLetterController` with the three signatures and the `{text, author, at}` view from `plan.md` § Interfaces, bodies unimplemented (after: T21) · `…/web/CoverLetterController.java`
- [x] T27 · ISC-259 · server — `CoverLetterService`: read the row, or the file for a package older than V29; save writes the file, then the row with author `edited` (after: T26) · `…/packaging/CoverLetterService.java`
- [x] T28 · ISC-259 · server — controller test: `PUT`, then the package zip carries the edit; `edited` in the row (after: T27) · `…test/web/CoverLetterControllerTest.java`
- [x] T29 · ISC-259 · web — `cover-letter.api.ts`, the three calls (after: T26) · `frontend/src/app/core/api/cover-letter.api.ts`
- [x] T30 · ISC-259 · web — `cover-letter.store.ts`, `@ngrx/signals` in the events dialect (after: T29) · `frontend/src/app/core/store/cover-letter.store.ts`
- [x] T31 · ISC-259 · web — the letter section in the application panel: textarea, Save, the author badge; shown from PACKAGED on (after: T30) · `frontend/src/app/features/offer-detail/application-panel/cover-letter/`
- [x] T32 · ISC-259 · web — the section's strings (after: T31) · `frontend/public/i18n/en.json`
- [x] T33 · ISC-259 · web — component spec: shown for a PACKAGED offer, a save calls the store (after: T32) · `frontend/src/app/features/offer-detail/application-panel/cover-letter/cover-letter.spec.ts`
- [x] T34 · ISC-260 · server — the draft call for one offer through writer, guard and fallback at one permit; `PUT` and the draft answer 409 at SENT, the draft 429 without a permit (after: T28) · `…/packaging/CoverLetterService.java`
- [x] T35 · ISC-260 · server — controller tests: one permit and new text, then both calls refused at SENT with the file unchanged (after: T34) · `…test/web/CoverLetterControllerTest.java`
- [x] T36 · ISC-260 · web — Regenerate beside Save, both disabled at SENT, the outcome through the toast layer (after: T33, T34) · `frontend/src/app/features/offer-detail/application-panel/cover-letter/`
- [x] T37 · ISC-261 · server — the build reads `cover_letter_author` first and writes an `edited` letter from the row instead of drafting (after: T27) · `…/packaging/PackagingService.java`
- [x] T38 · ISC-261 · server — build tests: the retry path and a rebuild leave an edited letter byte-identical (after: T22, T37) · `…test/packaging/PackagingServiceTest.java`

### Slice 4 — documentation and choosing the model

- [x] T39 · ISC-263 · [P] · server — `writing` in the shipped `pipeline.yaml` says what reads it · `backend/src/main/resources/leadgen/pipeline.yaml`
- [x] T40 · ISC-263 · docs — `pipeline-scoring.md`: the "only key nothing reads" paragraph and § The application package now name the model, the guard and the template as fallback (after: T17) · `docs/decisions/pipeline-scoring.md`
- [x] T41 · ISC-263 · docs — the fifth configuration file (after: T3) · `docs/CONFIGURATION.md`
- [x] T42 · ISC-263 · docs — the three columns on `offer` (after: T20) · `docs/DATA-MODEL.md`
- [x] T43 · ISC-264 · docs — `measure_cover_letters.ts`: calls the draft endpoint for a list of offer ids, stores text and author under a gitignored directory named by a CLI label, and renders a shuffled, unlabelled sheet; offers with an edited letter are refused (after: T34) · `docs/samples/measure_cover_letters.ts`
- [x] T44 · ISC-264 · operator — the three example letters and the banned phrases in the local override (after: T3) · `config/cover-letter.yaml`
- [ ] T45 · ISC-264 · operator — one bake-off pass per candidate model on five German offers, the blind ranking, the winner set as `LLM_MODEL_WRITING` (after: T43, T44) · `.env`

## Probe Mapping

| Task | Claim | Probe (from `spec.md` § Test Strategy) |
|------|-------|----------------------------------------|
| T1–T3 | ISC-258 | `ConfigLoaderTest`, `LeadGenRuntimeHintsTest` |
| T4–T6 | ISC-252 | `CoverLetterGuardTest` |
| T7, T8 | ISC-253 | `CoverLetterGuardTest` |
| T9, T10 | ISC-254 | `CoverLetterGuardTest` |
| T11, T19 | ISC-256 | `PackagingServiceTest` |
| T12, T15, T16, T25 | ISC-250 | `PackagingServiceTest` |
| T13, T14 | ISC-251 | `CoverLetterWriterTest` |
| T17, T18 | ISC-255 | `PackagingServiceTest` |
| T20–T22 | ISC-257 | `PackagingServiceTest`, `CoverLetterControllerTest` |
| T23, T24 | ISC-262 | `PackageArchiveServiceTest` |
| T26–T33 | ISC-259 | `cover-letter.spec.ts`, `CoverLetterControllerTest` |
| T34–T36 | ISC-260 | `CoverLetterControllerTest` |
| T37, T38 | ISC-261 | `PackagingServiceTest` |
| T39–T42 | ISC-263 | `rg` on `pipeline.yaml` and `pipeline-scoring.md` |
| T43–T45 | ISC-264 | the operator's blind ranking |
