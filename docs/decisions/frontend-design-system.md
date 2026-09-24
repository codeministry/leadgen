# The design system and the interface language

Two themes, one accent with one meaning, the navigation, the tokens, and the two catalogs behind them.

These are working notes moved out of `CLAUDE.md` so the always-loaded file stays small.
Every paragraph here was paid for once; none of it is a summary.

## The design system

- **Two themes, `lg-light` and `lg-dark`,** declared as `@plugin "daisyui/theme"` blocks in
  `src/styles.css` with DaisyUI's own built-ins switched off (`themes: false`) so they do
  not ship dead. All values OKLCH with the sRGB hex in a comment beside each, and
  `theme-colors.spec.ts` holds every pair to that and refuses a value outside the sRGB gamut,
  because a clamped value has no twin. By day: **Teal & Magenta**, a teal primary
  (`oklch(52% 0.088 185)`, which is the sRGB chroma ceiling for a teal of that lightness;
  the hue is gamut-poor and cannot be much more vivid than the old petrol without going
  lighter) on a cool neutral page. By night: a lavender primary on an indigo-black surface,
  the same design, and the default since 2026-09-24. The first light theme of the
  renovation was Indigo & Magenta and was dropped in the operator's review for looking like
  a colleague's product; the dark theme was judged good as written and kept.
- **The signal means one thing: this survived the filter.** `--color-accent` is magenta
  (`oklch(58% 0.22 345)` / `oklch(76% 0.17 345)`), chosen because every warm hue collides
  with warning amber and the reservation needs a hue nothing else uses. `--lg-signal` and
  `--lg-signal-text` are the app's aliases. It is read by the score's strong band, the funnel
  rail's survivor bar, the accent badge, the stat tile's emphasis, the rules screen's swatch,
  the three charts whose first series is the survivors, and — on the operator's rule that the
  brand always takes the bright tone — the brand mark's dots and the wordmark's second half.
  Nothing else: the syntax highlighting moved to `--lg-code-*`, the "answered" series to
  primary, and `ISC-224` in spec 003 holds the allowlist at ten files. The score bands
  follow it — `--score-strong` the signal, `--score-weak` **secondary**, `--score-out` muted.
- **The signal fill is tuned for objects (3:1), its text twin for labels (4.5:1).**
  `--lg-signal-text` is the darker magenta a 14px label takes, the strong score figure among
  them; `--lg-warning-text` and `--lg-muted` exist for the same reason. Every text pair and
  every object the app paints is measured in headless Chromium by `contrast.browser.spec.ts`
  under both themes, which is the gate the repository did not have before spec 003.
- **Nothing in the header re-scopes `data-theme`.** The dark plate the old bitmap needed is
  gone with the bitmap: the mark is inline SVG in the theme's own primary and the signal,
  measured at 3:1 on both surfaces, and the brand link sits on `base-100` like everything
  else. The `--lg-*` corrective tokens still re-scope with `data-theme` wherever it is nested.
- **The navigation is a row in the header, and it is the same seven links at every width.** It used to be a left rail
  that collapsed to icons; the row keeps that collapsed presentation between 48rem and 90rem and drops the wordmark with
  it, and below 48rem it becomes the fixed bottom bar the rail already turned into. Nothing hides behind a disclosure:
  seven destinations are the application's map, and a map behind a click is a map nobody reads. What carried over is the
  vocabulary of "you are here" — the primary colour, a 16 % wash and a 2px marker, rotated from the left edge onto the
  bottom one. Since spec 003 the marker and the wash take the section's own colour, `--lg-section`,
  and the label stays ink; the signal stays out of it.
- **The bottom bar sizes its shares on the `<li>`, not on the link.** `ul` lays out the list items, so `flex: 1 1 0` on
  the anchor inside one sizes nothing, and seven content-sized items run off a 375px screen. Measured: the seventh was
  cut off by the screen edge while `scrollWidth` reported no overflow at all, because the bar is `position: fixed` and
  clips instead of growing the page. At 320px two labels ellipsise, which is the accepted floor.
- **The header is sticky, and that is what lets a screen hand its scroll to the document.** A header that scrolls away
  takes the run button and the theme toggle with it, and on a long advert they are gone for the whole read.
  `z-index: 30` is above the 20 the bottom bar takes; `position:
  sticky` does not create a containing block, so the fixed bar still resolves against the viewport even though it now
  lives inside the header. Sticky is inert on a `.shell.fill` screen, where nothing scrolls at all.
- **The navigation is content-sized above 48rem, and that is what the empty bar was.** It used to claim `flex: 1` on the
  host, on `.topnav` and on `ul` while the `<li>` stayed content-sized, so the row swallowed every spare pixel and
  packed the links against the brand — a thousand pixels of nothing between the last link and the run button on a wide
  monitor, which is what "unevenly distributed" was pointing at. `.ops` still pushes itself right with an auto margin,
  so the space lands between the two groups instead of inside one. Below 48rem the growth is load-bearing and stays: the
  fixed bar is the whole width and `flex: 1 1 0` on the `<li>` needs a growing `ul`.
- **One gutter and one chrome gap, both tokens.** `--lg-gutter` is the inline padding of the content *and* of the header
  row; they used to be 1.5rem and 0.75rem, so at every width the brand mark began 12px left of the first thing on the
  page — permanent, small, and impossible to point at. `--lg-content-gap` is the vertical distance between the two:
  1.5rem against a 3.5rem bordered bar is a ratio of 0.43 and reads as content pressed against chrome, so it is 2.5rem,
  dropping back to 1.5rem below 48rem. **`--lg-sticky-top` is derived from it** rather than written, which removes a
  jump that was already shipping: the offset was `header-h + 0.75rem` against 1.5rem of padding, so a pinned list column
  moved 0.75rem the moment it docked, and at 2.5rem that jump would have been 1.25rem and looked like a bug. The
  `100svh` subtraction in both split stylesheets reads `--lg-gutter` for the same reason. The `< 48rem` override lives
  in
  `tokens.css` and not in the shell's stylesheet, because Angular's emulated encapsulation appends its attribute to
  every selector it is given — `:root` included, which then matches nothing. The gutter narrows to 1rem below 48rem and
  the header follows it: a phone is where the margin is worth the most and where a 7px disagreement between chrome and
  page is most visible.
- **The brand link carries `margin-inline-end: 0.75rem` on top of the row's gap.** The lockup is an identity and the
  seven links are a map; at the row gap alone they read as one group and the first destination looks like part of the
  wordmark. It sits on the brand and not on the nav, so nothing moves below 48rem where the nav leaves the row entirely.
- **The settings popover recomputes the row's right edge.** A popover is in the top layer, whose containing block is the
  viewport whatever `position` says, so `absolute` inside the capped row would not follow it either. `inset-inline-end`
  is
  `max(var(--lg-gutter), calc((100vw - var(--lg-shell-max)) / 2))`. **`inset: auto` in front of it is load-bearing:**
  the UA gives `[popover]` `inset: 0` with `margin: auto`, so an unset `left`
  stays 0 and the panel pins itself to the *window's* left edge at every width — measured exactly that way once, left 0
  and right 190 at 2560, while `:popover-open` and `aria-expanded` both reported the truth. `100vw` includes the
  scrollbar, so this one is checked in a real browser rather than reasoned about.
- **Theme, language and the version live in a popover, and the version is split in two.** They are three things read
  once against two that are operated on every screen, and the two radiogroups cost 220px of a bar the navigation now
  needs. A native `popover` rather than `role="menu"`, which would impose menuitem semantics and a roving tabindex both
  toggles violate, and rather than a modal dialog, which would trap focus for "switch to dark"; `aria-expanded` is
  mirrored from the panel's own `toggle` event, because `popovertarget`'s implicit state is not evenly supported. The
  panel's content is rendered whether it is open or not — a closed popover still contributes its text, and an `@if`
  around the version would take it out of the DOM and out of `app.spec`, far from anything that names it. The version's
  *digits* moved in; `connecting` and the error stay in the bar, because a backend that stopped answering must not need
  a click to be noticed.
- **`system` is the absence of `data-theme`.** DaisyUI emits `lg-dark` under
  `:root:not([data-theme])` inside a `prefers-color-scheme` query, so removing the
  attribute *is* "follow the operating system". An inline script in `src/index.html`
  applies the stored preference before first paint and shares the `lg-theme` key with
  `core/theme/theme.model.ts`.
- **The default is `light`, and it is written rather than implied.** `system` is still one of the three choices; it is
  no longer what an unconfigured browser gets, because the palette is designed light-first and a reader on a dark-set
  machine used to meet the dark variant before ever seeing the light one. Since the absence of the attribute *is*
  "follow the OS", the default cannot be expressed by leaving it off: the inline script writes
  `data-theme="lg-light"` when nothing is stored, and `DEFAULT_PREFERENCE` in
  `core/theme/theme.store.ts` is the other half of the same decision. Two places, kept in step by hand, exactly like the
  `lg-theme` key itself.
- **Fonts are self-hosted through `@fontsource-variable`,** never a CDN: Archivo (display,
  `standard.css` for the wght + wdth axes; the titles run on the width axis, 88 to 94, the
  larger the tighter), Instrument Sans (body) and Geist Mono (anything compared down a
  column, `tnum` alone because its zero is slashed by default). Bricolage Grotesque, Manrope
  and JetBrains Mono left with spec 003. The rule stands: a number you compare is mono, a
  number you admire is display. The twelve `type-*` utilities in `tokens.css` are the scale,
  one notch tighter and heavier since the renovation (h1 1.75rem/700, body 0.9375rem at 1.5).
- **Icons go through `<lg-icon>`,** which renders `lucide`'s icon node arrays directly.
  `lucide-angular` pins `@angular/core: 13.x - 21.x` and cannot be used on Angular 22.
  Each icon is a named import in `shared/icon/lucide-icons.ts` so esbuild can tree-shake.
- **The mark is the lead ring, drawn once and inlined.** `frontend/brand/mark.svg` is the one
  drawing: the score ring from the shortlist, open at the top right, with the lead inside, chosen
  by the operator from three candidates on one sheet (spec 003, 2026-09-24). Two groups carry
  two colours — `body` is the ring on `--color-primary`, `signal` the two dots on `--lg-signal`,
  the brand's accent by the operator's rule that the brand always takes the bright tone — so it
  cannot be a CSS mask, which paints one colour; `shared/brand-mark/` inlines the same paths and
  fills them by group, with `canvastext` under `forced-colors`. `favicon.ico` and
  `favicon-256.png` come from the SVG through `frontend/tools/build-favicon.sh`, which recolours
  by group id (no pixel box any more) and puts the mark on a round plate in the dark theme's
  surface with the dark theme's lavender and magenta, because a tab has no theme and dark is the
  default. The script is deterministic: two runs leave `git diff` empty. The old bitmap sources
  left the tree with the funnel they drew.
### The renovation (spec 003, 2026-09-24)

What changed in one pass, and why each piece is the way it is. The claims and the
measurements are in `specs/003-visual-renovation/spec.md`.

- **Three action tiers, literal classes.** `btn-primary` is the one filled call to action per
  screen area (nine in the app, and `button-tiers.spec.ts` caps it at one per template with
  dialogs allowed for); `btn-soft btn-primary` is the secondary tier — available, not urgent —
  chosen over the neutral soft because a teal-tinted chip reads as an action that is not the
  one action, where a grey chip reads as a plain button again; `btn-ghost` is close, back,
  clear, reveal and the square icon buttons. Segmented `join-item` selectors are a state
  control and exempt. `btn-outline` is retired both as a tier and as the "filter not at
  default" marker it used to be; that state is a primary dot at the sort trigger's corner and a
  heavier label on the filter trigger, whose count pill already said it.
- **The toast's link is a filled button in the toast's tone** (`btn btn-xs btn-<tone>`, from
  the literal `TOAST_LINK_CLASS` map). It was a ghost inside a tinted alert, which is the
  "Open" the operator could not find; a soft button on a soft tint has no boundary either.
  Measured per tone and theme: the label on the fill and the fill on the tint.
- **Seven section colours, orientation only.** `data.section` on the seven top-level routes,
  typed so a misspelling fails the build; the shell reads it off the deepest route and writes
  `data-section` on its host, so a child route inherits it — the offer detail is the
  shortlist's colour under one parent and the pipeline's under the other, on purpose. Same
  lightness and chroma across the seven per theme, hues 210, 160, 285, 55, 100, 255 and 315,
  at least 30° apart and at least ΔE-OK 0.10 and 25° from the signal (the ΔE floor alone let a
  section sit on the signal's hue at lower chroma). Exactly three stylesheets read
  `--lg-section`: the nav's marker and wash, a short bar under the `h1` in the page header,
  and the top edge of `.lg-panel` in `primitives.css`, the primitive that replaced four
  identical `.panel` rules and the stat tile's utility surface. A primary button never reads
  it. Panels and stat tiles carry the edge; lane cards, offer cards and the sources table stay
  neutral, so the colour stays a frame and not a fill.
- **Denser by measurement.** At 1440×900 on the shortlist the mean card went from 316 to 285px
  and the first card from 226 to 223, root font unchanged at 15px. The height came out of the
  reasons, clamped to two lines by Tailwind's own `line-clamp-2` — a hand-written clamp lost
  its `-webkit-box-orient` to the build's prefixer and rendered one line — and out of the
  reason line height, the panel padding (1rem) and the content gap (2rem).
- **Motion is tokens.** Four pairs in `motion.css` (hover, reveal, score, drag) replaced
  twelve duration literals in nine files, and stylelint's
  `declaration-property-value-disallowed-list` refuses a literal on `transition` or
  `animation` anywhere else, `motion.css` exempt.
- **The browser tier exists.** `bun run test:browser` runs every `*.browser.spec.ts` in
  headless Chromium through the Angular builder with its own setup file (no canvas stub), is
  wired into `./gradlew check` and CI, and cleared G-FE-01 in the constitution. jsdom resolves
  neither custom properties nor `oklch()`, so it is the only tier that can say no to a colour.
- **Three traps, paid for once.** The clamped span was called `.label`, which daisyUI ships
  with `white-space: nowrap` — the `.status` trap again. The new body face is wider than
  Manrope, so an agency badge and the detail's action row overflowed a 320px screen and now
  shrink and wrap. And the DOM-render screenshot mixed both themes in one capture while the
  palette was changing, so review pictures come from the headless verifier.

### The control room (spec 006, 2026-09-24)

The dashboard was a control panel for the pipeline and is a control room for the person who runs
it now. The claims and the measurements are in `specs/006-dashboard-control-room/spec.md`.

- **Bento, direction C.** The Designer agent offered three directions — an editorial "Morning
  Edition" with the top three offers as cards, a bento control room, and a lighter refresh — and
  recommended the first; the operator chose the bento, the densest and most precise of the three.
  What A had that C keeps: one number in the signal as the first read, one call to action, the
  machine room out of the lobby. A four-column grid, the hero at two by two, four cells beside it,
  one column below 48rem; at 1440×900 the five cells end at 276 and 408px, well inside the fold, the two upper cells at one height.
- **The hero is the one place a display-size figure takes the signal.** `.lg-panel-hero` in
  `primitives.css` washes the panel with 6 % of the section colour (`--lg-hero-wash`, a
  `color-mix`, never a literal) and draws the edge one pixel heavier; the figure is the signal's
  4.5:1 text twin at `type-display-xl`. The funnel rail gained a `compact` input — the seven
  totals in one line, the survivor bar, the share — rather than a second component, so the
  1,289 / 239 / 18.5 % baseline test runs over both forms.
- **One slim endpoint.** `GET /api/v1/analytics/summary` answers three groups — fourteen days of
  intake, the score bands, the last run's health — and the dashboard never requests
  `/api/v1/analytics`, whose payload carries every run, tag and portal. The query is recorded in
  `read-side.md`.
- **Two inline SVG charts in `shared/chart`.** The sparkline paints what came in on the primary at
  0.55 opacity and the shortlisted share in the signal; the bands paint strong in the signal, weak
  in secondary, the rest muted. Both carry a screen-reader table. `shared/` imports nothing from
  `core/`, so their day and band shapes are local types in `spark-day.ts`. The signal allowlist
  grows by the two components' five files and by the hero template, to sixteen; ISC-268 holds the
  count now, and ISC-224's ten is history.
- **The machine room is a `<details>`.** The per-source table, the stage timings, the model name
  and the archive note sit under the grid, closed unless the last run failed or a source
  mismatched. Closed, its summary line carries a preview — when, how many sources, how many new,
  the duration, the model — because five cells over a closed disclosure read as an empty page,
  the operator's note after the first cut, and under it four labelled lines of the run's own
  numbers (sources, hard filter, stages, outcome), after his second look found one line still
  lost under five cells. The line that says a run is going stays outside it.
- **The reader's words.** No "extracted", "written", "rows" or "documents" as a cell label; the
  intake legend says "came in" and "shortlisted". The machine room's table keeps the pipeline's
  columns, because that table is the pipeline's. Nothing counts up: the cells reveal on
  `--lg-reveal-*`, off under reduced motion, and no timer exists in the feature.
- **One more class trap.** `.hero` is a daisyUI grid that centres its content, which is the
  `.status` and `.label` trap a third time; the hero cell is `.control-hero`.

### The anchor rail (spec 007, 2026-09-24)

Analytics, sources, review and rules are long pages of stacked panels, and nothing on them said
what was on the page or took the reader to a section. The claims and the measurements are in
`specs/007-anchor-navigation/spec.md`.

- **One component, wrapping the screen.** `shared/anchor-rail` takes the sections — an id and a
  catalog key each — as an input and projects the screen's content as the column beside its
  own, so the two-column layout exists once and not in four stylesheets. A screen that declares
  no sections gets no column: the host falls back to a block. The rail could have read the
  headings out of the DOM; it takes a declared list instead, because a heading inside a panel
  that renders once data arrives would appear late and reorder the rail, and because the
  catalog key is what the link should say. The screens therefore declare their sections from
  the same signal that renders the panels — analytics and rules once the view is there, sources
  adding the panel's two headings once the panel has rendered — so a link never names a heading
  that does not exist yet.
- **The browser navigates, the rail only moves focus.** Each link is a real anchor to `#id`: the
  hash follows a click, the link can be copied or opened in a new tab, and the heading lands
  under the sticky header through `.lg-anchor-target` in `primitives.css`, whose scroll margin is
  the sticky offset plus the panel's padding and edge so the panel's coloured top edge comes to
  rest under the header rather than the heading's baseline. What the browser does not do is
  move focus into the section, so the click handler focuses the heading — every target carries
  `tabindex="-1"` for that reason — and never returns it to the rail.
- **Scroll-spy through an observer, decided by geometry.** An `IntersectionObserver` on the
  headings is the trigger only; on each callback the current section is the last heading whose
  top has passed the upper 40 % of the viewport, with the last section taken at the page's
  bottom, where a short final section never reaches the band. The observer is dormant in a
  backgrounded tab and absent in jsdom, so the hand-over is measured live through the headless
  verifier and the unit tier asserts the anchors, the focus landing and the empty case.
- **The marker is the section colour and nothing else.** A 2px edge on the current link in
  `--lg-section`, the text staying base-content because the section colours are tuned for 3:1 as
  objects, not 4.5:1 as text; the ISC-230 allowlist grows by this one stylesheet, to four. Below
  48rem the column is gone and the links are a row of chips under the page header, scrolling
  sideways inside themselves; the current chip takes the section colour as its ring.
- **Two screens have no headings where the anchors land.** The sources table's title is the page
  header, and the review's two panes were labelled by `aria-label`; both gained screen-reader-only
  headings, which also turn the panes' labels into `aria-labelledby` and give the outline what it
  was missing. `--lg-anchor-w` is 11rem, wide enough for the longest German section name.
- **Two leftover panel rules went with it.** `analytics.css` and `rules.css` still carried the
  pre-primitive `.lg-panel` rule; in a component stylesheet it beats the layered primitive, so the
  rules panels had lost their section edge and both screens had a padding of their own. Removed;
  `offer-detail.css` carries the same copy and is left for its own change.

### The workflow view (spec 008, 2026-09-24)

The rules screen became the workflow view: the run in order, each stage with what it costs and
what a model takes part in. The claims and the measurements are in
`specs/008-rules-workflow-view/spec.md`.

- **`--lg-ai` and `--lg-ai-surface` are a new semantic token pair, one violet the signal does
  not use.** The signal keeps its one meaning — this survived the filter — so a stage where a
  model takes part gets its own hue rather than borrowing it. The violet matches the `model`
  class of the scoring diagram in `docs/BACKEND-FLOWS.md` §1e (`fill:#e2d5f1,stroke:#6f4aa8`),
  so a reader who has seen that diagram recognises the colour on the screen. Defined once per
  theme in `styles.css`, like every other semantic pair.
  `rg -l "lg-ai" frontend/src` names only `styles.css`, files under `features/rules` and the
  contrast spec that measures it — nothing else reads it, which is ISC-309's own gate.
- **Two uses, never a third.** The rail marks a stage in the `model` cost class, or an ingest
  source that sends the extraction prompt, with a sparkle icon carrying `--lg-ai` on its edge
  and an accessible name (`rules.ai.marker`); the detail pane repeats the same stage as a head
  band in the same colour, naming it an AI step (`rules.ai.band`) and, where the last run named
  a model, which one (`rules.ai.model`). Both read `isAi()` off the same stage, so the two
  markers can never disagree about which stages are AI steps and which are not.
  `FILTER` is deterministic and carries neither.
- **Contrast measured in the browser tier, the same gate as the signal.** `contrast.browser.spec.ts`
  holds the icon to ≥ 3:1 as an object, on the selected row too, and the band's text to ≥ 4.5:1,
  in both themes, headless Chromium — jsdom cannot answer a contrast question, only report the
  OKLCH values it was given.

### The help drawer and the run confirmation (spec 010, 2026-09-24)

- **A native `<dialog>`, opened modally, is the drawer.** The platform gives Escape, the
  backdrop and the focus trap; the drawer only adds the side placement and the slide-in from the
  motion tokens, off under reduced motion. Focus returns to the help button on every close.
- **It opens at the chapter of the screen it was opened from**, read from the route's
  `data.section`; anything without a section opens the how-it-works chapter. "← All chapters"
  leads to a contents list, one full-width row per chapter with an icon and a one-line hint, so
  no chapter name has to fit a button. The drawer is `--lg-help-w` wide and never wider than the
  window; the chapter title is the drawer's own heading, so the Markdown carries none.
- **The texts are static Markdown per language under `public/help/`**, fetched only while the
  drawer is open and rendered by `shared/markdown`. They name no product and no configured value,
  and a spec keeps English and German in step.
- **The diagrams are built, not rendered in the browser.** Mermaid sources under
  `src/help/diagrams/` are rendered by `bun run help:diagrams` into one SVG each, committed, and
  stamped with the hash of their source and the script; a spec fails when either changes without
  a re-render. The render feeds Mermaid's `base` theme placeholder colours and rewrites them to
  `var(--…)` tokens, and the drawer inlines the SVG, so it takes the app's font and follows the
  theme with no second file. No Mermaid code ships in the bundle, and neither Docker nor CI needs
  a browser.
- **"Run ingest" asks first.** The run starts from the confirm action of its own dialog, so the
  header keeps its one primary button and the dialog has its own; cancel and Escape start
  nothing.

## The interface language

`frontend/src/app/core/i18n/` plus the two catalogs in `frontend/public/i18n/`. Transloco,
two languages, English the fallback.

- **English is the fallback because English is this repository's language.** A key nobody
  translated shows the sentence that was written, never a blank — and Transloco's own
  missing handler returns the key, so a *server* sentence passed through the pipe renders
  as itself. That is what lets the plain-text reason behind a rejected upload keep working.
- **`system` is a state, not the absence of one**, exactly as in the theme store: it
  resolves to the browser's language and the toggle still shows `system` as chosen. The
  same shape (`withHooks` + an `effect` on the resolved value) for the same reason — the
  I/O is the DOM and localStorage, not HTTP.
- **No prose is written in TypeScript.** A nav item, a band filter and a field row carry a
  catalog key; the template pipes it. A sentence assembled in a component (`review`'s
  summary, the dashboard's share) returns a key and its parameters instead, because the
  number sits in a different place in every language.
- **Plurals are ICU, through `transloco-messageformat`.** "1 listings" is the kind of wrong
  that only appears on the one day a run finds exactly one, and German declines
  differently — a rule per language belongs in the catalog, not in a ternary.
- **The catalogs are static files under `public/i18n/`, not bundled.** A translation fixed
  at five in the afternoon should not need a rebuild to reach the browser.
- **Transloco is provided globally in the tests** (`src/test-setup.ts`, with the real
  English catalog). Without it a spec fails with `No provider found for
  TRANSLOCO_TRANSPILER` from inside a component that has nothing to do with i18n, and a
  stub catalog would let the templates and the catalog drift apart unnoticed.
- **The server's own prose is not translated and this is the boundary.** Filter-stage
  descriptions, lane labels, knockout labels and every score reason arrive as English
  sentences from the API and stay English in both languages. Translating them means the API
  handing over an id and the browser holding a catalog keyed by it — which is the one thing
  the read side deliberately does not do today.

### Toasts

One line at the edge of the screen after a write or a run, and the decisions that shaped it
(spec `002-action-feedback-toasts`, 2026-09-23):

- **One mechanism, and it listens.** `core/toast/` turns a store's *answer* event into a
  message — `archived`, `updated`, `rescored`, `settled`, `finished`, the heartbeat's first
  sight of a run — and `layout/toast-stack/` paints it once, from the shell. No screen
  dispatches a toast for its own action. The same write has two or three callers already
  (archive from the detail and from a card, status from the board and from the detail), and
  a toast written per caller disagrees the first time one of them changes. Raised from the
  answer, "a refused move raises none" is free: `updated` fires only when the server said yes.
- **Failures stay inline.** Every refused write already paints a `role="alert"` paragraph
  beside the control that can retry it, and each placement was argued for when it was made.
  A toast beside that paragraph is one failure said twice. The stack is a `role="status"`
  region with `aria-live="polite"`, and nothing in it is an error.
- **A link, never an undo.** "Restore" on an archive toast reads as a lossless undo and is
  not one: the package is discarded on archive unless the application was ever sent, and
  a status change back over `PACKAGED` is a 409. The toast links to the offer, the card or
  the dashboard, and the reversal happens there under the rules that already hold. That is
  also what keeps the stack free of a second write path — its one control closes it.
- **One toast per run, whoever started it.** The operator's own run fires both paths, the
  report and the `run-ended` read-back; both carry `finishedAt`, and the stream keys on it.
  The start is keyed on the run id and raised only from the heartbeat, which `IngestStore`
  asks at once on a click so the operator's own toast is not an idle cadence late. The
  dedupe lives in the stream, not in state, because the order in which a reducer and a
  handler see one event is not something to depend on.
- **The container is `.lg-toasts`.** It was `.stack`, and DaisyUI 5 ships a `stack`
  component that fans its children over one another in one grid cell — three quick toasts
  showed as one card with two edges behind it. The `.status` trap again; the stack's spec
  now refuses any unprefixed class that is not `toast` or `alert*`.
- **Below 48rem the pile sits above the bottom bar.** At the top it covered the page title
  and the Views/Archive controls, the row a person reads first; above the bar it covers
  advert prose, which scrolls. The reveal's duration and easing are the first tokens in
  `src/styles/motion.css`, off under `prefers-reduced-motion`; the lifetime and the cap
  are constants beside the model, because the timer that reads them is TypeScript.
- **The DOM-render screenshot never paints the fixed stack.** The toast stood in the DOM
  with a measured rectangle while the capture showed nothing. The pictures in the spec
  come from a pixel capture over CDP, taken past the reveal; a frame inside it reads as a
  translucent toast and is not one.
- **A tone per action family, and amber means "taken away" here.** Two tones made an archive
  and its restore identical. Now: green for what is brought back, confirmed or moved forward
  (restore, confirmed document, scored rescore, a status change into any state but LOST,
  REJECTED and EXPIRED); amber for what is taken off the list or closed against us (archive,
  bulk archive, rejected document, those three states); blue for news nobody here asked for
  (runs, a rescore still unscored); never red. Amber has meant "wants attention" elsewhere
  (`shared/badge`, the rescore refusal); the neutral alert was the recommendation, following
  "everything discarded is muted", and the operator chose amber for the contrast, knowing the
  widening (2026-09-23). No icons: the colour and the sentence are enough at three tones.
