---
spec: 005-tailored-cover-letter
created: 2026-09-24T12:00:00Z
updated: 2026-09-24T12:00:00Z
rounds: 3
---

<!-- CONTEXT LOG — a record, not an authority. Nothing here gates anything and nothing
     reads it back. Every answer that changes the build lives in spec.md or plan.md. -->

# Context 005 — The cover letter is written against the ad

## Goal — confirmed 2026-09-24T12:00:00Z

When an application moves to PACKAGED, the configured writing model drafts the cover letter from
the ad's requirements, its contact and the profile's skills and reference projects, every claim in
it checked against the profile before it is written; without a model, or when the draft fails the
check, the template writes the letter as today; and the offer detail view shows the letter, saves
an edit and regenerates it until the application is sent.

Principal's words, verbatim: "Die Mailvorlagen jedes Offers soll verbessert werden und wenig nach KI klingen. zudem sind sie zugeschnittener als die Skill und Projekte, auf die sich die Anforderungen bezieht."

The prompt also carried three example letters produced on a job portal. They are personal content
(a named client, a named contact, the operator's history) and are therefore not quoted here; per
the constitution they belong in the gitignored `config/cover-letter.yaml` as style examples.

## Round 1 — before the spec, 2026-09-24T12:00:00Z

### Q1 · Which sentence should be the goal of spec 005?
- Offered: the model writes, template fallback (recommended) | a better template, no model | the
  model letter plus viewing, editing and regenerating it in the UI
- Chosen: the model letter plus editing in the UI
- Landed in: ## Goal, ISC-250, ISC-257, ISC-258

### Q2 · When is the draft created, and where is it edited?
- Offered: during the PACKAGED build, edited afterwards (recommended) | on demand in the detail
  view before PACKAGED, copied into the folder at PACKAGED
- Chosen: during the PACKAGED build
- Landed in: ISC-250, ISC-257, ISC-259; § Constraints (the package invariant holds)

### Q3 · Where does the model learn the operator's tone?
- Offered: style rules plus example letters in an overridable config file (recommended) | rules
  only | letters the operator edited and sent, picked automatically
- Chosen: rules plus examples in config
- Landed in: ISC-254, ISC-256

Read from the repo instead of asked: the letter is `cover-letter.{lang}.ftl` rendered by
`PackagingService.build()`; the greeting is always the neutral one; only `core` skills are matched,
by keyword; `contact` and `agency` never reach the letter; `llm.models.writing` is declared and read
by nothing; `AdvertAsker` is the existing pattern for a bounded, self-checked model answer;
`LeadGenRuntimeHints` covers every file under `leadgen/` by pattern.

Asked mid-session, answered outside the spec: which local models suit German prose. Candidates and
the bake-off that picks one go to `plan.md`; no model name is written into this folder.

## Round 2 — before the plan, 2026-09-24

### Q1 · In which order is spec 005 built?
- Offered: guard and style config first, then the writer in the build, then the UI, the bake-off
  last (recommended) | the bake-off script first, then the build | backend only, the UI as its own spec
- Chosen: guard first, then model, then UI
- Landed in: plan.md § Approach

Read from the repo instead of asked: the package columns live on `offer` (`V7`, `V27`);
`ChatModels.of(llm, model)` already resolves any configured model name; `PackageArchiveService.discard`
nulls the package columns in one `UPDATE`; `LeadGenRuntimeHints` covers `leadgen/*.yaml` by pattern;
`POST /offers/{id}/fetch` sets the precedent for a synchronous per-offer model-backed action.

## Still open

- none

## Round 3 — during build, 2026-09-24

### Q1 · ISC-252: how should the claim read after the second look found two gaps?
- From: T4–T10 (ISC-252), lane `server`
- Offered: exempt the chosen projects' stack and narrow the claim to what a lexical guard can decide (recommended) | exempt the stack only | keep it strict
- Chosen: exempt the stack, narrow
- Landed in: ISC-252 (refined master-first), spec § Out of Scope, § Decisions

### Q2 · ISC-251: where does the name in the salutation come from, given the contact column holds no names?
- From: T12–T14 (ISC-251), lane `server`
- Offered: from the ad text, honorific required, checked verbatim (recommended) | honorific-only from the contact column | always neutral for now
- Chosen: from the ad text
- Landed in: ISC-251 (refined master-first), § Decisions; the contact extraction gap in the master's Remaining Work

### Q3 · Local style examples
- Chosen: the operator's three example letters go into the gitignored `config/cover-letter.yaml`, with "hervorragend", "genau das" and "ein konkreter Mehrwert" removed because they are on the banned list
- Landed in: nowhere tracked — operator configuration (T44)

The operator then asked to skip the remaining browser test and commit; the build was committed as
`cb10b66`, the closure as `edbba74`, the three UI findings of the last second look as `a9dee85`.
