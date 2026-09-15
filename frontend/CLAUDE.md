# CLAUDE.md — frontend

Angular 22 zoneless, `@ngrx/signals`, Tailwind 4 / DaisyUI, bun. The repo-wide rules and the
invariants are in the root `CLAUDE.md`; this file holds what applies only here, and Claude
Code loads it when it reads a file in this tree.

The reasoning behind the screens lives in `docs/decisions/frontend-split-views.md` and
`docs/decisions/frontend-design-system.md`.

## Frontend conventions

Carried over from a sibling Angular project, which is the house style:

- **Layering is strict**: `shared` → `core` → `layout` → `features`. Cross-layer imports
  go through the tsconfig aliases (`@core/*`, `@shared/*`, `@features/*`, `@layout/*`),
  because that is what the `no-restricted-imports` patterns in `eslint.config.mjs` match
  on — a relative `../../core/...` slips past the rule. Relative imports only between
  siblings. **No barrels** (`index.ts`).
- **`shared/` imports nothing from the layers above it**, not even types.
- **Standalone components, signals, `OnPush`, zoneless.** `input()`/`output()`/`model()`,
  `signal`/`computed`, `inject()`, `@if`/`@for`. No `@Input/@Output`, no `*ngIf`, no
  `| async`. RxJS only at the I/O boundary, bridged in with `toSignal`.
- **`.css`, never `.scss`** — Tailwind 4 is CSS-first. `color-no-hex` is on globally and
  `src/styles.css` is the **only** exempt file, so every colour literal lives there beside
  the two DaisyUI themes; `src/styles/tokens.css` holds semantic aliases (`--score-*`),
  layout constants and the type scale, and references `var()` only.
- **NgRx**: `@ngrx/signals` events dialect, stores as a `*.store.ts` + `*.events.ts`
  pair with `withReducer` + `withEventHandlers`. Model: `core/store/status.store.ts`.
  Where the I/O is the DOM rather than HTTP, `withHooks` + an `effect` replaces
  `withEventHandlers` — see `core/theme/theme.store.ts`.
- **Specs live beside their file.**
- **Strict TypeScript** plus `strictTemplates`, `noPropertyAccessFromIndexSignature`,
  `noImplicitReturns`, `noImplicitOverride`, `noUnusedLocals`. No `baseUrl` — TypeScript
  6 deprecates it and the path mappings resolve relative to `tsconfig.json` anyway.

## Traps that have already cost money

- **Renaming the root component's selector means editing `src/index.html` too.** The
  Angular CLI generates `<app-root>`; the repo prefix is `lg-`. Every unit test still
  passes with the mismatch, because `TestBed` creates the component itself — the only
  symptom is a blank page in the browser, with no console error. Found exactly that way
  in step 1, so: verify a UI change in a real browser, not only in the suite.
- **A component class name must not collide with a DaisyUI component class.** DaisyUI 5
  ships `status` (a 0.5 rem dot) and `label`, among others. A header span classed
  `.status` was laid out as an 8 px box with its text overflowing under the next control,
  and nothing reported a problem. Check a new class name against DaisyUI's component list,
  or prefix it.
- **Tailwind 4 scans source *text* for class names, so a class assembled at runtime is
  never emitted.** `'badge-' + tone()` left seven of eight badge tones with no colour at
  all; measured against the built stylesheet, only `badge-accent` and `badge-outline`
  existed. Spell every variant out in a literal lookup map, or the class exists only in
  the DOM and never in the CSS.
- **A spec's `provideRouter` has no `withComponentInputBinding()` unless you pass it**, and a
  routed component then keeps every input at its declared default: the component renders, the
  effect that reads the input never fires, and the request it would have made is simply absent.
  The failure surfaces as "expected one matching request, found none" with nothing pointing at
  the router. `app.config.ts` provides the feature; a `TestBed` has to as well.
- **Router input binding writes `undefined` for an absent query parameter**, overriding
  the input's declared default. The first `q().trim()` on it throws inside the template
  and leaves the page half-rendered — the page title empty, half the controls gone, and
  nothing in the console pointing anywhere near the cause. Every routed input needs
  `transform: (value) => value ?? <default>`.
- **`min-height: 100%` breaks at `<lg-root>`,** which has no height of its own, so the
  percentage chain has nothing to resolve against and the nav rail ended at its last menu
  item. `100dvh` has no such dependency.
- **The flex item is the component host, not the element inside it.** Styling `.rail`
  without `:host { display: flex }` leaves the host at its inline default, and the child
  never stretches.
- **A bare boolean attribute binds as the empty string** (`outline`, not
  `[outline]="true"`), which `strictTemplates` rejects — but only at build time.
  `tsc -p tsconfig.app.json` in `check:static` does not run the Angular template compiler,
  so `bun run test` or `bun run build` is the gate that catches template type errors.
  Passing `check:static` says nothing about the templates.
- **A backgrounded tab suspends CSS transitions,** and `getComputedStyle` then returns the
  transition's *start* value rather than its target. An active nav link read as muted grey
  while being correct in a real browser. Anything transitioned, animated, or driven by
  `ResizeObserver`/`IntersectionObserver` must be measured through the Interceptor skill's
  `Tools/VerifyViewport.ts`, never through a background tab.
- **A CSS transition never fires on first paint.** A width rendered correctly the first
  time never changes, so nothing animates and the reveal silently does not exist. It needs
  two states: render the start value, let the browser paint it, then set the target —
  `afterNextRender` plus one `requestAnimationFrame`. See `shared/funnel-rail/`.
- **`repeat(auto-fit, minmax(21rem, 1fr))` cannot shrink below its minimum**, so a panel
  grid pushed the page sideways at 320 px while looking fine everywhere else. Write
  `minmax(min(21rem, 100%), 1fr)`. In the same family: a flex item will not go below its
  content width without `min-width: 0`, which is what let a 20 rem search input overflow a
  320 px screen.
- **Test horizontal overflow at the page, not at the element.** Comparing every element's
  right edge against the viewport flags the kanban board and the wide tables, which scroll
  inside their own `overflow-x: auto` on purpose. The real check is
  `document.scrollWidth > document.clientWidth`.
- **The DOM-render screenshot is evidence about layout and colour, not about state or
  reflow.** It serialises and re-renders, which drops DOM properties that have no attribute (a `<select>`'s selection),
  some component CSS on SVG children (`fill` on the score ring),
  and it mis-measures text that wraps inside a flex item — three separate false alarms in
  one session. Read the accessibility tree for widget state (`interceptor read` prints
  `combobox … value="SENT"`), and confirm a suspected overlap in a real browser before
  changing CSS. The Angular dev server sets a CSP that blocks `interceptor eval`, so the
  geometry cannot be measured through it either.
- **A componentless leaf route is refused outright.** `{ path: ':name' }` with neither component, `loadComponent`,
  `redirectTo`, `children` nor `loadChildren` throws `NG04014`
  when the router config is validated — which happens when the `Router` is constructed, so every spec that merely
  injects it fails, far from the route that caused it.
- **An author `display` on a popover keeps it open forever, and every API you would ask says it is closed.** What hides
  a closed popover is the UA rule `[popover]:not(:popover-open) { display: none }`, which carries no `!important`, so a
  `display: flex` on the panel's own class beats it. The panel then stands open on the page while `:popover-open`
  reports `false`, `aria-expanded` reports `"false"` and the click still toggles the state correctly. Put `display` on
  `:popover-open` and nowhere else. Shipped exactly that way once and found by a person looking at the screen: the
  screenshots showed it open, the probe asked the API, and the screenshot was the one that got explained away as a
  rendering artifact.
- **jsdom has no `document.scrollingElement`, and it is typed `Element | null`.** It arrives `undefined`, walks straight
  through a `!== null` guard and takes down every spec of the screen that reads it with "Cannot set properties of
  undefined" — ten at once, from inside an effect, far from anything that names scrolling. The guard has to be truthy.
  Same family as the next one, and found the same way.
- **jsdom implements `scrollTop` but not `Element.scrollTo`.** A scroll reset written as
  `scrollTo({ top: 0 })` passes `tsc`, works in the browser, and takes down every spec that renders the component with
  `scrollTo is not a function` from inside an effect.
- **Safari intermittently keeps the folded height of an unfolded advert.** Measured on the page: `max-height: none`,
  `overflow: visible`, no mask, and the box still exactly 390px, the clamp's own value, with the text running on behind
  the panels below it. Six isolated variants of the structure — scroll pane, grid, spanning panel, mask, the whole
  height chain — were all correct in the same Safari, and the same page measured correctly a minute later.
  `OfferDetail.relayoutAd` detaches the box and reads a metric off it after the toggle. It is a workaround on an
  observation, not on a reproduced cause, and it says so.
- **ImageMagick renders SVG with its own parser and drops paths containing arcs** unless
  `rsvg-convert` is on PATH as its delegate. The first favicon looked broken for that
  reason alone, with the geometry perfectly correct.
