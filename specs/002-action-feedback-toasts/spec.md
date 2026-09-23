---
task: "Feedback after an action, and for a run whoever started it"
slug: 002-action-feedback-toasts
spec_type: feature
isa_master: ../../ISA.md
isa_feature: F30
constitution: ../constitution.md
phase: scoping
progress: 15/16
started: 2026-09-23T16:40:00Z
updated: 2026-09-23T21:55:00Z
principal_stated_goal: "Der User soll in der App Feedback über Aktionen oder Ereignisse erhalten"
principal_stated_goal_source: prompt
principal_stated_goal_signal: 3
principal_stated_goal_locked: 2026-09-23T16:44:00Z
context_sufficient: true
interview_invoked: false
context_log: context.md
---

<!-- SPEC — a derived view of ../../ISA.md (feature F30). Claim IDs belong to the master.
     Sync: Skill("Spec", "sync 002-action-feedback-toasts"). Never edit the master from this file.
     principal_stated_goal is the one German string in this folder: the format keeps the principal's
     words byte for byte, and these carry no value the constitution keeps out of specs/. -->

# 002 — Feedback after an action, and for a run whoever started it

## Problem

A write confirms itself today only by its side effect. Archive an offer and the row leaves the list; move a card and
it lands in the next lane; rescore and the ring changes. That is enough while the eye is on the thing that changed,
and it is nothing at all when it is not: the archive button sits in the detail column while the list is what moved,
the bulk archive clears a selection and says so in a count under a heading, and a status change made from the offer
detail happens on a board the reader is not looking at. Failures have a place — every refused write paints a
`role="alert"` paragraph beside its control — and successes have none.

Runs are worse. A pass takes eleven minutes on the deployed corpus and most of them start from the nightly schedule.
`RefreshStore` already notices the moment one ends and tells every store to read again, so the screens are right;
the person is told nothing. The shortlist shows a stale hint, the dashboard shows new numbers, and neither says "a run
just finished" to somebody on the pipeline board.

## Vision

He archives an offer from the detail and a line at the edge of the screen says so, with the title, and is gone by the
time he has read it. He moves a card and the line names the state it landed in. He is on the board at eight in the
morning when the nightly pass ends, and the line says the run wrote a hundred and twelve offers and shortlisted four,
with a link to the dashboard. Nothing asks for a click, nothing steals the cursor, a screen reader hears each line
once, and the same line never appears twice for one event, whichever screen wrote it and whoever started the run.

## Out of Scope

- **Failures.** Every refused write already has an inline `role="alert"` paragraph beside the control that can retry
  it, and a toast beside that paragraph is one failure said twice. Decided 2026-09-23. A failed write that has no
  inline place would earn a toast; today none is without one.
- **Undo.** An archive toast with "Restore" on it would promise a lossless undo it cannot keep: archiving discards the
  package unless the application was ever sent, and a status change back over `PACKAGED` is refused by the transition
  map. A toast links to the thing it names and the reversal happens there, under the same rules as today.
- **A history.** No list of past toasts in the header. The event log on an application and the dashboard's run panel
  already hold what is worth keeping; a toast is what is not.
- **Server-sent events.** The heartbeat that already polls `/api/v1/ingest/current` is what makes another tab's run
  visible; this spec adds no push channel.
- **Toasts for reads.** A page loading, a filter applied, a detail opened: the screen is the feedback.

## Constraints

- **One mechanism** (DS-APP-25): one store that turns domain events into messages, one stack that paints them, one
  stylesheet with a z-index. A second way to say "done" is the failure this rule names.
- **The store listens; screens do not dispatch.** A toast is raised from the store event that carries the server's
  answer — `archived`, `updated`, `rescored`, `settled`, `finished` — never from a component's click handler, so the
  detail and the card cannot disagree about what a write said.
- **Strict layering** (FE-LAYER-01..04): `core/toast/` may import the other stores' event groups; `layout/toast-stack/`
  reads `core/toast/` and nothing from `features/`. Cross-layer imports go through the aliases.
- **Events dialect** (FE-STATE-01): `toast.store.ts` + `toast.events.ts`, `withReducer` + `withEventHandlers`,
  like every other store in `core/store/`.
- **The catalog owns every sentence.** Keys under one `toast` group in `public/i18n/en.json` and `de.json`, counts as
  ICU plurals through `provideTranslocoMessageformat`. The server's own stage names stay English inside them, as
  everywhere else.
- **Tailwind scans source text.** Every `alert-*` tone is spelled out in a literal lookup; a class assembled at
  runtime exists in the DOM and not in the stylesheet (`frontend/CLAUDE.md`).
- **The accent has one meaning** (`docs/decisions/frontend-design-system.md`): the score and the interaction. A toast
  takes `alert-success`, `alert-info` and their kin, never the accent.
- **No colour literal outside `src/styles.css`** (DS-APP-04). The duration and the easing are tokens in
  `src/styles/motion.css`, the file DS-APP-07 names and the repo does not have yet, not literals in the component.
- **Reduced motion is honoured** (DS-APP-42) and **verified in a foregrounded real browser** (DS-APP-43); a
  backgrounded tab suspends transitions and a DOM-render screenshot cannot see one.
- **Native semantics before ARIA** (DS-APP-37): a live region with `role="status"`, a `<button>` to close, a
  `routerLink` to navigate. Nothing gets `role="alert"` here, because nothing here is an error.
- **`manualEvents.settled` changes shape** to carry the outcome. One event, two dispatchers, one reducer; the change
  is in `plan.md` § Interfaces.
- **jsdom.** A spec that renders the stack must not touch `Element.scrollTo` or `document.scrollingElement`, and
  timers run fake.

## Goal

Every write a person makes in the browser and every ingest run that begins or ends, whoever started it, is confirmed
by exactly one short toast that names what happened and links to it, raised by one mechanism anchored in the shell,
announced once to assistive technology, gone by itself, while every failure keeps the inline place it has today.

## Features

### F30 · Feedback after an action, and for a run whoever started it

**Why:** A write confirms itself today only by its side effect — the row leaves the list, the card moves — and a run
that ends while nobody is on the dashboard is news to no one; the shell has no place that says what just happened.

- [x] ISC-205: One mechanism: `core/toast/` (`toast.store.ts` + `toast.events.ts`, events dialect) is the only place a domain event becomes a message, and `layout/toast-stack/`, rendered once by the app shell, is the only place one is painted; no screen dispatches a toast for its own action.
- [x] ISC-206: An archive or a restore of one offer raises one toast that names the direction and the offer's title and links to `/shortlist/:id`, raised from `shortlistEvents.archived` so the detail and the card produce the same toast. (after: ISC-205)
- [x] ISC-207: A bulk archive raises one toast naming the count the server wrote, never the count asked for, through the catalog's ICU plural. (after: ISC-205)
- [x] ISC-208: A status change raises one toast naming the application's title and its new state and linking to `/pipeline/:id`, from `applicationEvents.updated` and never from the optimistic `changed`, so a refused move raises none. (after: ISC-205)
- [x] ISC-209: A rescore raises one toast naming the offer's title and the score the server stored, from `shortlistEvents.rescored`. (after: ISC-205)
- [x] ISC-210: A manual document leaving the inbox raises one toast naming the document and whether it was confirmed or rejected; `manualEvents.settled` carries the outcome for it. (after: ISC-205)
- [x] ISC-211: A run beginning raises one toast, whether this browser pressed the button or the heartbeat first saw a run in flight, and never a second one for the same run id; a run this browser starts is seen by the heartbeat at once rather than at the idle cadence. (after: ISC-205)
- [x] ISC-212: A run ending raises one toast naming what it wrote and shortlisted and linking to `/dashboard`, from the report when this browser started it and from the recorded last run that `run-ended` reads back otherwise, keyed by `finishedAt` so a run this browser started raises one toast although both paths fire, and a last run read on startup raises none. (after: ISC-205)
- [x] ISC-213: Anti: a load failure or a refused write raises a toast; the inline `role="alert"` paragraphs stay where they are. (after: ISC-205)
- [x] ISC-214: A toast leaves by itself after a duration held as a token, stays while hovered or focused, closes on its button, and no more stand at once than the cap held beside the duration, the oldest leaving first. (after: ISC-205)
- [x] ISC-215: The stack is a polite live region — `role="status"`, `aria-live="polite"`, a new toast appended into it — its close button has an accessible name, and no toast takes focus. (after: ISC-205)
- [ ] ISC-216: Entering and leaving are gated by `prefers-reduced-motion`, and the reveal is verified in a foregrounded real browser. (after: ISC-214)
- [x] ISC-217: Every toast text is a key under one `toast` group present in both catalogs with the same key set, and every count in them is an ICU plural. (after: ISC-205)
- [x] ISC-218: Anti: a toast carries a control that writes; its only control closes it, and its link is a router navigation to a route that already exists. (after: ISC-205)
- [x] ISC-219: The toast takes its tones from spelled-out DaisyUI `alert` classes, holds no colour literal, and takes the accent nowhere. (after: ISC-205)
- [x] ISC-220: `docs/decisions/frontend-design-system.md` carries the toast decisions, `frontend/CLAUDE.md` gains at most one rule line, `CHANGELOG.md` § Unreleased names the feature, and `WorkingNotesStaySmallTest` stays green. (after: ISC-205)

## Test Strategy

| isc | type | check | threshold | tool | anchors_to |
|---|---|---|---|---|---|
| ISC-205 | bash | `rg -n 'toastEvents\.' frontend/src/app --glob '!**/core/toast/**' --glob '!**/toast-stack/**'`; the shell spec counts `lg-toast-stack` | 0 hits; rendered once | rg, Vitest | `core/toast/`, `app-shell.spec.ts` |
| ISC-206 | bun-test | dispatch `shortlistEvents.archived` for an archived and for a restored entry | two toasts, direction and title each, link `/shortlist/<id>` | Vitest | `toast.store.spec.ts` |
| ISC-207 | bun-test | dispatch `bulkArchived` with five ids and `archived: 3` | one toast naming 3 | Vitest | `toast.store.spec.ts` |
| ISC-208 | bun-test | dispatch `changed` then `updated`; dispatch `changed` then `changeFailed` | one toast after `updated` naming the state; none for the failed pair | Vitest | `toast.store.spec.ts` |
| ISC-209 | bun-test | dispatch `rescored` | one toast naming the stored score | Vitest | `toast.store.spec.ts` |
| ISC-210 | bun-test | dispatch `settled` with each outcome | one toast each naming the document and the outcome | Vitest | `toast.store.spec.ts`, `manual.events.ts` |
| ISC-211 | bun-test | `requested` then `currentLoaded(run 7)` twice; `currentLoaded(run 8)` alone | one toast for 7, one for 8; `currentRequested` dispatched on `requested` | Vitest | `toast.store.spec.ts`, `ingest.store.spec.ts` |
| ISC-212 | bun-test | `finished(@T)` then `run-ended` + `lastRunLoaded(@T)`; `run-ended` + `lastRunLoaded(@U)`; `lastRunLoaded(@V)` with no reason | one, one, none; written and shortlisted named | Vitest | `toast.store.spec.ts` |
| ISC-213 | bun-test | dispatch `archiveFailed`, `bulkArchiveFailed`, `rescoreFailed`, `changeFailed`, `failed` | 0 toasts; `rg -c 'role="alert"' frontend/src/app` unchanged | Vitest, rg | `toast.store.spec.ts` |
| ISC-214 | bun-test | fake timers: one toast past the duration; one hovered past it; four raised at once | gone; still standing; three standing, the first gone | Vitest | `toast-stack.spec.ts`, `toast.store.spec.ts` |
| ISC-215 | bun-test | render the stack with one toast | `role="status"` and `aria-live="polite"` on the region, close button named, `document.activeElement` unchanged | Vitest | `toast-stack.spec.ts` |
| ISC-216 | manual | archive an offer in a real foregrounded browser, once with reduced motion on | animates; does not with the preference set | Interceptor `VerifyViewport.ts` | `toast-stack.css` |
| ISC-217 | bun-test | compare the `toast` key sets of `en.json` and `de.json` | identical; every count key carries `plural` | Vitest | `i18n-parity.spec.ts`, `public/i18n/` |
| ISC-218 | bun-test | `rg -n 'injectDispatch\|Dispatcher' frontend/src/app/layout/toast-stack`; resolve every toast link against `app.routes.ts` | only `toastEvents` (dismissed, held, released), no domain event; every link a configured path | rg, Vitest | `toast-stack.ts`, `toast-links.spec.ts`, `app.routes.ts` |
| ISC-219 | bash | `bun run lint:css`; `rg -n "'alert-' \+" frontend/src/app`; `rg -n accent frontend/src/app/layout/toast-stack` | green; 0; 0 | stylelint, rg | `toast-stack.css`, `src/styles.css` |
| ISC-220 | bun-test | `./gradlew :backend:test --tests WorkingNotesStaySmallTest`; `rg -n -i toast docs/decisions/frontend-design-system.md CHANGELOG.md` | green; ≥ 1 each | JUnit, rg | `WorkingNotesStaySmallTest`, `frontend-design-system.md`, `CHANGELOG.md` |

## Decisions

- **2026-09-23 — Every write and every run, not the three examples.** The request names three events with "Bsp.",
  and the confirmed goal reads them as examples: a bulk archive, a rescore and a settled manual document are writes a
  person makes and are confirmed the same way. Runs count whoever started them, because the heartbeat already sees a
  scheduled pass and the transition it raises is the one that matters most — it is the run nobody was watching.
- **2026-09-23 — Failures stay inline.** Every refused write already paints a `role="alert"` paragraph beside the
  control that can retry it, and each of those placements was argued for when it was made (`rescoreError` beside the
  button rather than blanking the detail, `bulkArchiveError` beside the action bar). A toast on top is one failure
  said twice, and moving the paragraphs into toasts would take the message away from the control. ISC-213 holds it.
- **2026-09-23 — A link, never an undo.** "Restore" on an archive toast reads as a lossless undo and is not one: the
  package folder is discarded on archive unless the application was ever sent, and `PACKAGED` is the transition the
  endpoint refuses to let anything skip, so a status change back is a 409 the toast would then have to show. The
  toast links to the offer, the card or the dashboard, and the reversal happens there under today's rules. That is
  also what keeps the stack free of a second write path (ISC-218).
- **2026-09-23 — The store listens to answers, not to requests.** Raised from `archived`, `updated`, `rescored`,
  `settled` and `finished`, which carry what the server wrote, never from `archiveRequested` or the optimistic
  `changed`: a toast for a move the server then refuses would confirm something that did not happen, and the board
  already puts the card back. The exception is the run start (ISC-211), where the heartbeat's first sight of a run
  is the answer.
- **2026-09-23 — One toast per run, keyed by what both paths carry.** `RefreshStore` documents that a run this
  browser started fires both `ingestEvents.finished` and `refreshEvents.requested('run-ended')`, a moment apart.
  `IngestReport` and `LastRunView` both carry `finishedAt`, so the end is keyed on it; `CurrentRunView` carries the
  run id, so the start is keyed on that and raised only from `currentLoaded`, never from `requested`. The price of
  raising the start from the heartbeat alone is up to thirty seconds of delay for the operator's own click at the idle
  cadence, so `requested` asks the heartbeat once immediately — one extra indexed read, and the toast is where the
  click is.
- **2026-09-23 — The reveal measured under both preferences, in a live lifecycle; ISC-216 waits for the operator's
  word.** Over CDP with `prefers-reduced-motion` emulated, rAF ticking at 28–29 per 400ms and the page visible: without
  the preference the `toast` animation runs 0.25s ease-out (opacity 0.11 → 1, scale 0.91 → 1); with it
  `animationName: none`, opacity 1 from the first frame, no running animation. The claim is `manual` and closes on
  the principal's own assessment, not on this measurement; a VoiceOver pass was not made.
- **2026-09-23 — The container is `.lg-toasts`, because `.stack` is a DaisyUI component.** The operator saw three
  quick toasts as one card with two edges behind it. Measured over CDP: the container computed as `display: grid`
  with the three alerts fanned by 11px, which is DaisyUI 5's `stack` component doing exactly what it is for. Renamed
  with the repo prefix, the container is the flex column DaisyUI's `toast` intends, the three stand at y 674, 747
  and 820 with the newest at the bottom, and the pile grows upward. `toast-stack.spec` now refuses any class on the
  region or an alert that is not `toast`, `alert*` or `lg-`-prefixed, since jsdom cannot see the fan. The same trap
  `frontend/CLAUDE.md` records for `.status`, hit again by the same hand.
- **2026-09-23 — Below 48rem the stack sits above the bottom bar, not under the header.** The mark said top-end.
  Measured at 390px through the headless verifier: at the top the toast covered the page title and the Views and
  Archive controls, the one row a person reads first; above the bar it covers advert prose, which scrolls. Full width
  there, bottom-end above. The same pass found that the DOM-render screenshot never paints the fixed stack — the
  toast stood in the DOM with a measured rectangle while the capture showed nothing — so the pictures come from a
  pixel capture over CDP, taken past the 0.25s reveal; a frame inside it reads as a translucent toast and is not one.
- **2026-09-23 — Marks, with their reasoned defaults.** ⟨?: six seconds and a cap of three — unmeasured; a run toast
  with two numbers wants longer than "archived", and six is the longer of the common defaults⟩ ⟨?: bottom-end above
  48rem, top-end below it, where the nav rail is a fixed bottom bar and owns that edge — not measured on a phone⟩
  ⟨?: the rescore toast stays although the new score is on screen beside the button — the goal names the rescore,
  and the same line reads better as one rule than as one exception⟩ ⟨?: the manual inbox counts as a write — the goal
  says every write, and confirm/reject is one, but the inbox is a screen the operator leaves rarely, so the toast
  there is the one most likely to be struck at review⟩.

## Verification

- ISC-205 — rg 'toastEvents\.' outside core/toast and toast-stack: 0 hits; app-shell.spec 'renders the toast stack exactly once' green (2026-09-23)
- ISC-206 — toast.store.spec 'raises one toast per answer, naming the direction and the offer, linking to it'; red under a direction mutation, green after; live on :4200: Archived/Restored toasts for offer 29745 at 1280 and 390
- ISC-214 — toast.store.spec 'leaves by itself once the lifetime has passed', 'stays while held, and starts a fresh lifetime when released', 'closes on the button and does not expire a second time', 'keeps no more than the cap, the oldest leaving first'; toast-stack.spec 'holds the timer under the pointer and releases it on leave'; cap mutation went red
- ISC-215 — toast-stack.spec 'is a polite live region that is in the DOM before it has anything to say', 'paints a raised toast … and steals no focus'; live: role=status aria-live=polite read over CDP
- ISC-217 — i18n-parity.spec 'hold the same keys in English and German' over the whole catalog, plus the count-key plural check
- ISC-219 — bun run lint:css green; rg "'alert-' +" over frontend/src/app: 0; rg accent in layout/toast-stack: 0 (2026-09-23)
- ISC-207 — toast.store.spec 'names the count the server wrote, never the count asked for'; red when the count was mutated to the ids asked for
- ISC-208 — toast.store.spec 'is confirmed from the row the server returned, naming the state and linking to the card' and 'raises nothing for a move the server refused'; a handler on `changed` does not type-check (no title on the request)
- ISC-209 — toast.store.spec 'names the score the server stored' and 'says so when nothing judged it'
- ISC-210 — toast.store.spec 'names the document and whether it was confirmed or rejected'; red when the outcome branch was mutated; `manualEvents.settled` carries `{name, outcome}`
- ISC-213 — toast.store.spec 'raises no toast at all' over seven failure events; `rg -c 'role="alert"'` = 14 before and after
- ISC-218 — rg over toast-stack.ts: `injectDispatch(toastEvents)` only, dismissed/held/released; toast-links.spec 'all resolve against the configured routes' (3 links)
- ISC-211 — toast.store.spec 'is announced once per run id, from the heartbeat and never from the request' (red without `distinctUntilChanged`); ingest.store.spec 'asks the heartbeat the moment a run is requested, and keeps asking fast while it is out'
- ISC-212 — toast.store.spec 'is announced once for a run this browser started, although both paths fire' (red without `distinctUntilKeyChanged`), '… from the last run read back', 'says nothing about a last run read for any other reason'
- ISC-220 — WorkingNotesStaySmallTest green (frontend/CLAUDE.md 10,740 of 12,000); rg -i toast: frontend-design-system.md 16, CHANGELOG.md 4, frontend/CLAUDE.md 2 (2026-09-23)
