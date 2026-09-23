---
spec: 002-action-feedback-toasts
type: feature
status: draft
updated: 2026-09-23
---

# Plan 002 — Feedback after an action, and for a run whoever started it

**Purpose:** how the claims in `spec.md` get built. The what lives there; this file
holds no acceptance criterion.

## Approach

One store that listens and one stack that paints, built as a vertical slice first: the store, the stack in the
shell, the `toast` catalog group, the motion token, the parity spec and exactly one mapping, the archive, verified in
a real browser before any other mapping copies its shape. Then the five remaining action mappings, each a handler and
a spec block, which can be written in parallel because they touch no shared file but the store. Then the two run
mappings, which are the hard part: the start keyed on the run id and raised only from the heartbeat, the end keyed on
`finishedAt` and raised from the report or from the last run that `run-ended` reads back, with `IngestStore` asking
the heartbeat once immediately on `requested` so the operator's own click is not thirty seconds late. Last, the
reduced-motion pass in a foregrounded browser and the documentation.

The obvious path was to raise toasts where the actions happen, a `dispatch.toast(...)` in each click handler. It was
not taken because the same write has two or three callers already (archive from the detail and from the card, status
from the board and from the detail) and the toast would then be written as many times and disagree the first time one
caller changed. Listening to the store events that carry the server's answer gives one mapping per event, and it is
also what makes "a refused move raises none" free: `updated` only fires when the server said yes.

The other order considered, runs first because the nightly run is the toast nobody else provides, was declined for the
build and not for the value: the run mappings need the stack and the catalog anyway, and the dedupe is easier to get
right against a stack whose shape is already settled by the cheap mapping.

## Affected Files and Modules

| Path | Change | Claim |
|------|--------|-------|
| `frontend/src/app/core/toast/toast.model.ts` | new: the `Toast` shape, the tone union and the literal tone-to-class lookup | ISC-205, ISC-219 |
| `frontend/src/app/core/toast/toast.events.ts` | new: `raised`, `dismissed`, `expired`, `held`, `released` | ISC-205, ISC-214 |
| `frontend/src/app/core/toast/toast.store.ts` | new: the list, the cap, the two run keys, one handler per domain event, the timer per toast | ISC-205 … ISC-214 |
| `frontend/src/app/core/toast/toast.store.spec.ts` | new: one `describe` per mapping, fake timers for the lifetime | ISC-206 … ISC-214 |
| `frontend/src/app/layout/toast-stack/toast-stack.{ts,html,css}` | new: the live region, DaisyUI `toast toast-end` + `alert`, close button, `routerLink`, hover and focus hold | ISC-205, ISC-214, ISC-215, ISC-216, ISC-218, ISC-219 |
| `frontend/src/app/layout/toast-stack/toast-stack.spec.ts` | new: region attributes, focus untouched, hold on hover, cap | ISC-214, ISC-215 |
| `frontend/src/app/layout/app-shell/app-shell.html` | render `<lg-toast-stack/>` once, outside `.measure` | ISC-205 |
| `frontend/src/app/layout/app-shell/app-shell.ts` | import the stack | ISC-205 |
| `frontend/src/app/layout/app-shell/app-shell.spec.ts` | new: the shell renders one stack | ISC-205 |
| `frontend/src/app/core/store/manual.events.ts` | `settled` carries `{name, outcome}` | ISC-210 |
| `frontend/src/app/core/store/manual.store.ts` | both handlers dispatch the outcome; the reducer reads `payload.name` | ISC-210 |
| `frontend/src/app/core/store/ingest.store.ts` | `requested` also dispatches `currentRequested` | ISC-211 |
| `frontend/src/app/core/store/ingest.store.spec.ts` | the immediate heartbeat ask | ISC-211 |
| `frontend/public/i18n/en.json`, `de.json` | the `toast` group, counts as ICU plurals | ISC-217 |
| `frontend/src/app/core/i18n/i18n-parity.spec.ts` | new: the flattened key sets of both catalogs are equal, whole catalog | ISC-217 |
| `frontend/src/styles/motion.css` | new: `--lg-toast-duration`, `--lg-toast-ease`, the reduced-motion override | ISC-214, ISC-216 |
| `frontend/src/styles.css` | `@import` the motion file beside `tokens.css` | ISC-214 |
| `docs/decisions/frontend-design-system.md` | the toast decisions, in the section on the interface language | ISC-220 |
| `frontend/CLAUDE.md` | one rule line: a toast is raised from a store's answer event, never from a screen | ISC-220 |
| `CHANGELOG.md` | § Unreleased: the feature, and the `settled` shape as a non-breaking internal change | ISC-220 |

Nothing under `backend/`. The API already answers everything the toasts say.

## Data Model

No persisted form changes. Two in-memory types move.

**`Toast`** (new, `core/toast/toast.model.ts`), before → after: nothing → 

```ts
interface Toast {
  readonly id: number;                       // monotonic, the stack's track key
  readonly tone: 'success' | 'info';         // the only two this spec raises; the lookup spells both out
  readonly key: string;                      // catalog key under `toast.`
  readonly params?: Record<string, unknown>; // title, count, state, written, shortlisted
  readonly link?: string;                    // a configured route: /shortlist/:id, /pipeline/:id, /dashboard
}
```

**`ToastState`**: `toasts: readonly Toast[]`, `held: number | null` (the toast under the pointer or focus),
`lastRunStarted: number | null` (run id), `lastRunFinished: string | null` (`finishedAt`),
`awaitingRunEnd: boolean` (a `run-ended` was seen and the next `lastRunLoaded` is the one to name).

**`manualEvents.settled`**, before → after: `type<string>()` → `type<{name: string; outcome: 'confirmed' | 'rejected'}>()`.

## Interfaces

| Contract | Before | After | Who calls it |
|----------|--------|-------|--------------|
| `manualEvents.settled` | the document name | the name and the outcome | `ManualStore` dispatches it from both handlers; `ManualStore`'s reducer and `ToastStore` read it |
| `ingestEvents.requested` | starts the run | starts the run and asks the heartbeat once at once | `AppHeader` dispatches; `IngestStore` handles both; `ToastStore` reads `currentLoaded`, never `requested` |
| `AppShell` template | header, outlet | header, outlet, one `<lg-toast-stack/>` | the root; nothing else renders the stack |
| `toastEvents.dismissed` | — | the one event the stack dispatches | `ToastStack`'s close button |

The domain events the store subscribes to are unchanged: `shortlistEvents.archived`, `bulkArchived`, `rescored`;
`applicationEvents.updated`; `manualEvents.settled`; `ingestEvents.currentLoaded`, `finished`, `lastRunLoaded`;
`refreshEvents.requested`.

## Risks

| Risk | Blast radius | Early warning | Mitigation |
|------|--------------|---------------|------------|
| Both run paths raise a toast for the operator's own run | one duplicate line per own run, every run | the ISC-212 spec's first case | key on `finishedAt`, which `IngestReport` and `LastRunView` both carry; the spec dispatches both paths for one instant |
| `lastRunLoaded` on startup raises a "run finished" for last night | one wrong toast on every page load | the ISC-212 spec's third case | `awaitingRunEnd` is set only by `refreshEvents.requested('run-ended')` and cleared by the next `lastRunLoaded` |
| The immediate heartbeat ask races the run row: the server has not opened it yet | the start toast is late by one idle cadence, not missing | the ISC-211 spec against a `currentLoaded(null)` first | acceptable; the fast cadence begins with the first non-null answer and the run is seen within thirty seconds at worst. Recorded here so nobody adds a retry |
| A live region with several children announces the whole region on each append | every standing toast read again | manual check with VoiceOver during the ISC-216 pass | `aria-live="polite"` on the region and `aria-atomic="false"`, which is the default; each toast is its own child |
| Fake timers under zoneless: an `effect` scheduled by a signal write does not flush with `vi.advanceTimersByTime` | the lifetime specs pass for the wrong reason or hang | the ISC-214 spec | timers live in the store's handler as RxJS `timer`, not in an `effect`; `TestBed.tick()` after advancing, as `applications.store.spec.ts` already does |
| `'alert-' + tone` assembled at runtime | a toast with no colour, in one theme, on one tone | `rg` in ISC-219 | the literal lookup in `toast.model.ts` |
| The stack sits on the fixed bottom nav below 48rem | the toast covers the nav or the nav covers the toast | the ISC-216 browser pass at 390px | the position mark in the spec: `toast-top` below the breakpoint, `toast-bottom` above |
| The `settled` shape change breaks a spec that dispatches it with a string | `manual.store.spec.ts` red | `bun run test` | update the dispatching spec in the same task |
| The full-catalog parity spec goes red on a key added later in only one catalog | CI red on an unrelated change | the spec names the key | that is the point; it clears G-FE-02 and the fix is the missing key |
| Toasts pile up during an eleven-minute run with the stage heartbeat | none: the start is raised once per id | the ISC-211 spec's "twice" case | `lastRunStarted` |

## Conformance Impact

| Baseline entry | Effect |
|----------------|--------|
| G-FE-02 i18n parity, grandfathered | **clears.** The catalogs already hold 464 keys each with no difference, measured 2026-09-23, so the parity spec is written over the whole catalog rather than over the `toast` group alone; ISC-217 is satisfied a fortiori. The baseline row moves to cleared when the claim closes |
| G-FE-01 browser tier and contrast, grandfathered | leaves. ISC-216 is a manual Interceptor pass, not a `*.browser.spec.ts`; the tier still does not exist |
| FE-TST-05 coverage ratchet, grandfathered | leaves |
| DS-APP-07 / DS-APP-41 motion file, not in the baseline | the repo has `tokens.css` and no `motion.css`; this plan creates `src/styles/motion.css` with the toast's duration and easing, which is where the rule puts them. Not a clearing, because the rule was never measured here; noted so the file's existence is not a surprise |
