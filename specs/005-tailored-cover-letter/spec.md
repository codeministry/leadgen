---
task: "Write the cover letter against the ad with the writing model, and let the operator edit it"
slug: 005-tailored-cover-letter
spec_type: feature
isa_master: ../../ISA.md
isa_feature: F33
constitution: ../constitution.md
phase: climbing
progress: 14/15
started: 2026-09-24T12:00:00Z
updated: 2026-09-24T14:00:00Z
principal_stated_goal: "Die Mailvorlagen jedes Offers soll verbessert werden und wenig nach KI klingen. zudem sind sie zugeschnittener als die Skill und Projekte, auf die sich die Anforderungen bezieht."
principal_stated_goal_source: prompt
principal_stated_goal_signal: 2
principal_stated_goal_locked: 2026-09-24T12:00:00Z
context_sufficient: true
interview_invoked: false
context_log: context.md
---

<!-- SPEC — a derived view of ../../ISA.md (feature F33). Claim IDs belong to the master.
     Sync: Skill("Spec", "sync 005-tailored-cover-letter"). Never edit the master from this file.
     principal_stated_goal is the one German string in this folder: the format keeps the principal's
     words byte for byte. It is the prompt's second and third sentence: the first named a job portal,
     which the constitution keeps out of specs/, and the example letters that followed are personal. -->

# 005 — The cover letter is written against the ad

## Problem

The application package's letter is `cover-letter.{lang}.ftl`, rendered by `PackagingService` when
an application moves to PACKAGED. It writes the same letter to every client: a neutral greeting,
the matched `core` skills joined into one comma-separated line, and the fixed pitches of the two
reference projects the ranking chose. What the ad actually asks for, who wrote it and which company
it comes from never reach the text; `contact` and `agency` are in the model and unused. Only the
`core` tier is matched, by keyword, so a requirement served by a `strong` skill is silently absent.

The result reads like a form, and a client reads it as one. The operator rewrites it by hand before
every application, which is the hour the tool exists to save. Letters produced by a model that was
given the ad and the profile read markedly better, and `llm.models.writing` was declared for exactly
this and is read by nothing.

## Vision

He moves an offer to PACKAGED. A few seconds later the application panel shows the letter: it
greets the person the ad names, says in one sentence why this project fits, maps the ad's must-haves
onto what he has actually done, lists five or six skills that the ad asked for and he has, names one
recent project, and closes with his availability. Nothing in it is a skill he does not have or a
project he did not do, and none of the phrases he has banned is in it. He changes one sentence,
saves, downloads the folder, and the edit is in it. On an offer where the draft did not pass, the
template letter is there instead, marked as such, and a click asks the model again.

## Out of Scope

- Generating a letter before PACKAGED, on any offer or in bulk. The package invariant stays as it is.
- Tailoring the CV. The fixed PDFs stay selected by language only (`CLAUDE.md` § Repo-wide invariants).
- Sending the letter, or a recipient field of any kind (`NothingIsSentTest`).
- Learning the tone from letters the operator edited or sent. The style comes from configuration.
- Choosing the writing model. The spec reads a configured one; which one is a local measurement,
  recorded in `plan.md` without a name.
- Removing the template. It stays the fallback and the only author when no model is configured.
- Recognising a skill the profile does not know when it is named only in the letter's body. No lexical
  check can tell it from ordinary prose; the prompt forbids it and the operator's blind reading
  (ISC-264) is where one surfaces.

## Constraints

- Rules before model (`CLAUDE.md`): without a writing model the tool writes the letter exactly as it
  does today, and every check on a draft is deterministic Java.
- The local provider answers and there is no automatic fallback (`CLAUDE.md`): a failed draft falls
  back to the template, never to another model. Every model call takes an `LlmBudget` permit first
  (`backend/CLAUDE.md`).
- A package is built when a person moves an application to PACKAGED, never by a run; archiving
  discards it unless the application was sent, and a restore comes back at `NEW` (`CLAUDE.md`).
- Two configuration layers (`CLAUDE.md`, `docs/decisions/configuration.md`): the new style file ships
  a neutral default on the classpath and is overridden file by file from `leadgen.config-dir`.
- A file reached by a computed name needs a hint in `LeadGenRuntimeHints` (`CLAUDE.md`).
- Schema changes are new Flyway migrations; applied ones are byte-identical (BE-DB-02, BE-DB-03).
  JDBC through `JdbcClient` (BE-DB-04). API paths are `/api/v1/…` (BE-API-03).
- The letter's language is the ad's language (`docs/decisions/pipeline-scoring.md`); the letter's
  text is content, the prompt, the code and the UI strings are English (`CLAUDE.md` § Language).
- No committed file names a model, a portal, a client or carries a personal example letter
  (constitution § What a spec may contain). The operator's examples live in `config/`.

## Goal

When an application moves to PACKAGED, the configured writing model drafts the cover letter from
the ad's requirements, its contact and the profile's skills and reference projects, every claim in
it checked against the profile before it is written; without a model, or when the draft fails the
check, the template writes the letter as today; and the offer detail view shows the letter, saves
an edit and regenerates it until the application is sent.

## Claims

- [x] ISC-250: With a writing model configured, moving an application to PACKAGED writes `cover_letter.txt` from that model's draft, in the language the ad is written in.
- [x] ISC-251: The drafted letter's salutation names the contact person only when the ad's own text names that person with an honorific, and is the neutral salutation of the letter's language otherwise. (after: ISC-250)
- [x] ISC-252: Every skill the draft declares, and every profile spelling of a skill in its body, occurs in `skill-profile.yaml` as a name or an alias of any tier and in the ad's own text or the stack of a chosen reference project; a draft failing that is rejected.
- [x] ISC-253: Every reference project the drafted letter names is one `ReferenceRanking` chose for that offer; a draft naming any other project is rejected.
- [x] ISC-254: A draft that contains a phrase from the banned list in `cover-letter.yaml`, or exceeds its word limit, is rejected.
- [x] ISC-255: Without a writing model, with the call budget spent, after a failed call or a rejected draft, the template writes the letter and the package build completes. (after: ISC-252)
- [x] ISC-256: Anti: a failed or rejected draft is never retried against any provider or model other than the configured writing model.
- [x] ISC-257: `meta.json` and the stored letter record its author as `model`, `template` or `edited`. (after: ISC-255)
- [x] ISC-258: `cover-letter.yaml` resolves over both configuration layers, its classpath default carries style rules and no example letter, and `LeadGenRuntimeHintsTest` stays green.
- [x] ISC-259: The offer detail view of a packaged application shows its letter, and saving an edit rewrites `cover_letter.txt` and the stored copy so the package download carries the edited text. (after: ISC-250)
- [x] ISC-260: Regenerating replaces the letter with a fresh draft at one budget call, and both editing and regenerating are refused once the application is SENT. (after: ISC-259)
- [x] ISC-261: Anti: neither the run's retry path nor a rebuild of the package overwrites a letter whose author is `edited`. (after: ISC-259)
- [x] ISC-262: Archiving discards the stored letter together with the package unless the application was ever sent, and a restored application that was never sent carries no letter.
- [x] ISC-263: `pipeline.yaml` no longer marks `llm.models.writing` as unread, and `docs/decisions/pipeline-scoring.md` names the model as the letter's author and the template as its fallback.
- [ ] ISC-264: On five real German offers the operator ranks, blind, the configured model's letters above the template's.

## Test Strategy

| isc | type | check | threshold | tool | anchors_to |
|---|---|---|---|---|---|
| ISC-250 | bun-test | stub writing `ChatModel` answering a valid draft; move a German and an English offer to PACKAGED | `cover_letter.txt` equals the draft's text; German ad → German letter, English → English | JUnit, Testcontainers | `PackagingServiceTest` |
| ISC-251 | bun-test | drafts for an ad naming "Frau <name>", for one naming no person, for a contact column holding an ad fragment, and for a model greeting a name the ad does not contain | the name in the first case only; the neutral salutation in the other three | JUnit | `CoverLetterWriterTest` |
| ISC-252 | bun-test | guard fed a draft naming a skill absent from the profile, one naming a profile skill absent from the ad and from every chosen stack, and one naming a chosen project's stack skill the ad omits | first two rejected; the third and a draft naming only profile skills present in the ad pass | JUnit | `CoverLetterGuardTest` |
| ISC-253 | bun-test | guard fed a draft naming a project `ReferenceRanking` did not choose | rejected | JUnit | `CoverLetterGuardTest` |
| ISC-254 | bun-test | guard fed a draft with one banned phrase, and one over the word limit | both rejected | JUnit | `CoverLetterGuardTest` |
| ISC-255 | bun-test | build with no writing model; with `LlmBudget` at zero; with the stub throwing; with the stub answering a rejected draft | 4 × template letter written, package row stamped | JUnit, Testcontainers | `PackagingServiceTest` |
| ISC-256 | bun-test | stub writing model throws while a scoring model is configured too | 0 calls to any model but the writing stub | JUnit | `PackagingServiceTest` |
| ISC-257 | bun-test | build once through the model, once through the template, then save an edit | `author` in `meta.json` and the row: `model`, `template`, `edited` | JUnit, Testcontainers | `PackagingServiceTest`, `CoverLetterControllerTest` |
| ISC-258 | bun-test | load with no config file, then with an override in the config dir; run the hints test | defaults carry rules and 0 examples; the override wins; hints test green | JUnit | `ConfigLoaderTest`, `LeadGenRuntimeHintsTest` |
| ISC-259 | bun-test | render the application panel for a PACKAGED offer and save an edit; `PUT` then download the package zip | letter shown; the zip's `cover_letter.txt` equals the edit | Vitest, JUnit, Testcontainers | `cover-letter.spec.ts`, `CoverLetterControllerTest` |
| ISC-260 | bun-test | regenerate once; then set the application to SENT, `PUT` and regenerate | 1 budget call and new text; both later calls answered 409, file unchanged | JUnit, Testcontainers | `CoverLetterControllerTest` |
| ISC-261 | bun-test | edit a letter, then run the packaging retry and a rebuild | file and row byte-identical to the edit | JUnit, Testcontainers | `PackagingServiceTest` |
| ISC-262 | bun-test | archive an unsent packaged application and restore it; archive a sent one | letter columns null after the first; kept after the second | JUnit, Testcontainers | `PackageArchiveServiceTest` |
| ISC-263 | bash | `rg -n 'not read yet' backend/src/main/resources/leadgen/pipeline.yaml`; `rg -n -i 'fallback' docs/decisions/pipeline-scoring.md` | 0; ≥ 1 in the package section | rg | `pipeline.yaml`, `pipeline-scoring.md` |
| ISC-264 | manual | five real German offers, the model letter and the template letter side by side, unlabelled | the operator's ranking | the operator | `cover-letter.yaml` |

## Decisions

- **2026-09-24 — A rejected draft goes to the template, not to a second attempt.** A retry would
  double the budget spent on exactly the offers where the model already misbehaved, and it makes the
  build's cost depend on the model's mood. The operator can press Regenerate, which spends the
  second call only when a person decided it was worth it. ISC-254 and ISC-255 say so.
- **2026-09-24 — SENT freezes the letter, and a restore carries none.** A sent letter is the record of
  what the client received; editing it afterwards would make `SENT` stand for a text nobody sent.
  An archive of an unsent application discards the folder, and the restore comes back at `NEW`, so
  there is no letter left to be editable. An application that was ever sent keeps folder and letter
  through an archive and a restore, so "sent" is read from the event log, not from the current
  status. ISC-260 and ISC-262 say so; ISC-262 was worded to that on 2026-09-24 after the second look.
- **2026-09-24 — The tailoring check is lexical, on purpose.** "Named in the profile and in the ad" is
  the cheapest test that catches both failures that matter: a skill the operator does not have, and
  a skill the ad did not ask for. Asking a model whether a letter is tailored would move the check
  behind the thing it checks.
- **2026-09-24 — refined: ISC-251 takes the name from the ad, not from the `contact` column.**
  The column never holds a person in the local corpus (0 of 165), so a salutation names someone only
  when the ad names them with an honorific, checked verbatim against the ad. Operator's choice.
- **2026-09-24 — refined: ISC-252 covers what a lexical guard can decide, and a chosen project's stack
  vouches for a skill.** Found by the second look: describing a chosen project by its stack named
  profile skills the ad omits and would have sent most letters to the template. A skill unknown to
  the profile, named only in the body, is out of reach and moved to § Out of Scope. Chosen by the
  operator in the build question round.
- **2026-09-24 — The guard claims do not wait for the writer.** ISC-252, 253 and 254 were minted with
  `(after: ISC-250)`. The plan builds the guard first and probes it on literal drafts, so the edge
  held three independently provable claims behind the model; it was removed in the master and here
  before `tasks.md` was written.

## Verification
- ISC-258 — `ConfigLoaderTest.theCoverLetterStyleShipsRulesAndNoExampleLetter` + `anOverrideInTheConfigDirectoryWinsForTheCoverLetterStyle` + `LeadGenRuntimeHintsTest` green in the main tree (`./gradlew :backend:test --tests de.codeministry.leadgen.config.* --tests LeadGenRuntimeHintsTest` exit 0); red before = compileTestJava failed on missing CoverLetterStyle; second look Max concerns → all six adopted (unguarded-language warning, banner lists the file, yaml says word_limit required, whole-map no-example assertion, fixture note, Javadoc counts) (2026-09-24)
- ISC-253 — `CoverLetterGuardTest` (unchosen project declared, named only in the body, and under its other-language title) green in the main tree; red before = guard absent; second look Max pass (2026-09-24)
- ISC-254 — `CoverLetterGuardTest` (banned phrase any case, in the salutation, stem catching an inflection, over the limit incl. non-breaking spaces, exactly at it) green in the main tree; red before = guard absent; second look Max concerns adopted: stems match inflections, Unicode word split, null-safe draft (2026-09-24)
- ISC-252 — `CoverLetterGuardTest` (skill absent from profile, profile skill absent from ad and chosen stacks, body-only profile skill, chosen-stack skill accepted, alias and strong-tier pass) 15/15 green in the main tree; chosen-stack case red on the pre-refinement guard; second look Max concerns: stack exemption and narrowing adopted as refined claim (2026-09-24)
- ISC-255 — `PackagingServiceTest.writesTheTemplate*` ×4 (no model, budget refused, throwing stub, banned-phrase draft) green in the main tree, full backend suite green; red before = writer unwired; second look Max: closed as written (2026-09-24)
- ISC-256 — `PackagingServiceTest.neverAsksAnyModelButTheWritingModelWhenItFails` (writer 1 call, scoring 0, `ChatModels.of` never) green; red before = writer unwired; second look Max: closed at the ChatModels seam, no bypassing caller in the tree (2026-09-24)
- ISC-250 — `PackagingServiceTest.writesTheLetterFromTheWritingModelsDraftInTheLanguageOfTheAd` (stub keyed on "Letter language", byte-equal de + en letters) and `asksTheWritingModelOutsideAnyDatabaseTransaction` (red with @Transactional) green; full backend suite green in an isolated tree copy (95 result files, 0 failures); second look Max: pass after F2–F4 rework (2026-09-24)
- ISC-251 — `CoverLetterWriterTest` (ad names Frau <name>, ad names nobody, fragment greetings, paragraph greeting, labels, short surnames, Dr.-companies, Herrn) + `PackagingServiceTest.greetsNobodyByNameWhenOnlyTheContactColumnHoldsAFragment` green; red before = contact-column heuristic greeted "CD Ers"; second look Max concerns, all six adopted (2026-09-24)
- ISC-262 — `PackageArchiveServiceTest.throwsAwayTheStoredLetterWithAnUnsentPackageAndRestoresNone` (red at the discard assertion) + `keepsTheStoredLetterOfAnApplicationThatWentOut` green; full suite 784/0 in the isolated copy; second look Max: holds, wording refined (2026-09-24)
- ISC-257 — `CoverLetterControllerTest.savingAnEditRewritesTheLetterThePackageDownloadCarries` (row + meta.json `edited`) and the model/template cases in `PackagingServiceTest` green; second look Max: holds (2026-09-24)
- ISC-259 — `CoverLetterControllerTest` (PUT, then the zip carries the edit) 12/0 and Vitest `cover-letter.spec.ts` green; second look Max: holds, two minor UI findings recorded as Remaining Work (2026-09-24)
- ISC-260 — `CoverLetterControllerTest` (one permit, 429 without one, 409 once ever sent incl. sent→archived→restored and sent→LOST) green, mutation on the gate red; second look Max: holds (2026-09-24)
- ISC-261 — `PackagingServiceTest.neitherTheRetryNorARebuildOverwritesAnEditedLetter` green (red before the passthrough); second look Max: holds (2026-09-24)
- ISC-263 — `rg "not read yet" pipeline.yaml` 0 hits; `pipeline-scoring.md` § The application package names model, guard and template fallback; CONFIGURATION, DATA-MODEL, BACKEND-FLOWS and CHANGELOG updated; second look Max: holds (2026-09-24)
