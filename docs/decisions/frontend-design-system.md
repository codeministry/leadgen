# The design system and the interface language

Two themes, one accent with one meaning, the navigation, the tokens, and the two catalogs behind them.

These are working notes moved out of `CLAUDE.md` so the always-loaded file stays small.
Every paragraph here was paid for once; none of it is a summary.

## The design system

- **Two themes, `lg-light` and `lg-dark`,** declared as `@plugin "daisyui/theme"` blocks in
  `src/styles.css` with DaisyUI's own built-ins switched off (`themes: false`) so they do
  not ship dead. Palette **Petrol & Ocker**, all values OKLCH. Light primary is petrol
  `#0E6E6B`; dark primary is the logo's own cyan `#33E3DA`
  (`oklch(83.04% 0.1348 189.53)`, 10.5:1 on `base-100`), which is also what the "LEAD"
  half of the wordmark takes.
- **Ochre means one thing: this survived the filter.** Petrol carries structure and
  interaction, everything discarded is muted. Nothing else may take the accent. The score
  bands follow it — `--score-strong` ochre, `--score-weak` **secondary**, `--score-out`
  muted. Review takes secondary rather than primary because the dark primary is the
  logo's bright cyan and would outshine the ochre; the middle band must never be the
  loudest thing on the screen.
- **`--color-accent` is a fill and a large-number colour, never body text.** It is 3.05:1
  on the sand page and fails AA below 24 px. `--lg-accent-text` is the text variant at
  4.68:1, and `--lg-muted` / `--lg-warning-text` exist for the same reason.
- **The mark is a mask, and nothing in the header re-scopes `data-theme` any more.** The asset is a flat single-colour
  silhouette — `tools/build-favicon.sh` already keys out the white and repaints from the alpha channel alone — so the
  only thing in it worth keeping is the shape.
  `.mark` fills it with `--color-primary`: petrol at 6.15:1 on white and 5.80:1 on the sand page, well past the 3:1 a
  graphical object needs, and on the dark theme that token *is* the logo's own cyan, so nothing visibly changes there.
  It also puts the mark and the wordmark's "LEAD" on one token; they used to be two different blues 20px apart. The dark
  plate it replaces was scoped with `data-theme="lg-dark"` on the brand link, and before that on the whole `<header>` —
  the navigation moving in ended the second one, because below 48rem that navigation is a fixed bottom bar and inside a
  dark-scoped header it would be a dark bar under a light page. What ended the first is a measurement: `#161F22` on the
  white bar is **16.8:1**, against 15.8:1 for body ink on the sand page. The plate was the highest-contrast object in
  the entire light theme and the only one carrying no information, which is why it read as pasted on rather than as a
  badge. The
  `--lg-*` corrective tokens still re-scope with `data-theme` wherever it is nested; the brand zone simply no longer
  needs it. A mask has no intrinsic ratio, so `BrandMark` writes both axes from the asset's own 116×128 box, and
  `forced-colors` gets an explicit fallback because a masked element there disappears outright.
- **The navigation is a row in the header, and it is the same seven links at every width.** It used to be a left rail
  that collapsed to icons; the row keeps that collapsed presentation between 48rem and 90rem and drops the wordmark with
  it, and below 48rem it becomes the fixed bottom bar the rail already turned into. Nothing hides behind a disclosure:
  seven destinations are the application's map, and a map behind a click is a map nobody reads. What carried over is the
  vocabulary of "you are here" — the primary colour, a 16 % wash and a 2px marker, rotated from the left edge onto the
  bottom one. Ochre stays out of it; it means "this survived the filter" and nothing else.
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
- **Fonts are self-hosted through `@fontsource-variable`,** never a CDN: Bricolage
  Grotesque (display, `opsz.css` for the wght + opsz axes), Manrope (body), JetBrains Mono (anything compared down a
  column). The rule is: a number you compare is mono, a number
  you admire is display. The twelve `type-*` utilities in `tokens.css` are the scale.
- **Icons go through `<lg-icon>`,** which renders `lucide`'s icon node arrays directly.
  `lucide-angular` pins `@angular/core: 13.x - 21.x` and cannot be used on Angular 22.
  Each icon is a named import in `shared/icon/lucide-icons.ts` so esbuild can tree-shake.
- **The brand mark is the real asset.** `shared/brand-mark/` renders
  `public/logo-mark.png` beside the two-tone LEADgen wordmark. `logo-mark.png` is
  `logo-1.png` cut out, trimmed and resized to 128 px tall — four times the 26 px the
  header shows. `favicon.ico` and `favicon-256.png` come from the same source on a round
  plate, so the tab icon and the header show one funnel; there the spout takes the accent
  and the plate is `#0E2C2D`, `base-200` pulled towards the petrol primary rather than the
  theme's near-black, because at 16 px the plate's hue is what carries the brand.
  `frontend/tools/build-favicon.sh` is the only thing that knows the spout's pixel box, so
  the icons are regenerated, never hand-edited. `logo-1.png` and `logo-2.png`
  stay as the untouched sources. The asset carries its own cyan and does not follow the
  theme; it sits on `base-100` in both, where it stays legible.

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
