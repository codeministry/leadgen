---
spec: 003-visual-renovation
created: 2026-09-23T21:36:00Z
updated: 2026-09-24T00:40:00Z
rounds: 3
---

<!-- CONTEXT LOG — a record, not an authority. Nothing here gates anything and nothing
     reads it back. Every answer that changes the build lives in spec.md or plan.md. -->

# Context 003 — The visual renovation

## Goal — confirmed 2026-09-23T21:05:00Z

Every screen of the frontend is repainted on a new, more vivid palette in both themes, with exactly
one colour reserved for "survived the filter", every action visibly in one of three tiers so the
primary call to action and a toast's link stand out from the ghost controls around them, each of the
seven sections carrying its own orientation colour on the nav marker, the page header and its panel
edges, new self-hosted fonts on a denser and higher-contrast rhythm, and a new mark and wordmark;
every text pair at 4.5:1 and every UI object at 3:1 in both themes, measured in a real browser as a
gate the repository did not have; behaviour, routes, stores, catalogs and every functional spec
unchanged.

Principal's words, verbatim: "design renovieren, Farbschema ändern und flotter machen, CTAs farblich
besser abheben (bsp problem: toasty grün, “Open”-Button hellgrau - hebt sich schwer ab). auf die
verschiedenen Bereich ein Farbcode einführen"

Added mid-turn, verbatim: "und das logo muss auch erneuert werden, ich finde das nicht mehr
ansprechend"

The request arrived as `/Spec refactoring …`. The goal lock widened it and changed the type: the
principal chose the complete renovation including layout and typography and said in as many words
that it is a feature rather than a refactor, because it is larger.

## Round 1 — before the spec, 2026-09-23

### Q1 · Which goal sentence? (the goal lock)

- Offered: three-tier CTA hierarchy plus a section colour code plus a fresher palette (recommended) |
  only the tiers and the section colour on the palette as it is | the complete renovation including
  layout and typography
- Chosen: the complete renovation, and the type moves from `refactor` to `feature`
- Landed in: `## Goal`, `spec_type`, ISC-222 … ISC-242

### Q2 · Does one colour keep the single meaning "survived the filter"?

- Offered: one reserved signal colour stays, its hue may change (recommended) | the reservation is
  lifted and the score bands take a plain semantic colour
- Chosen: one reserved signal colour stays
- Landed in: ISC-224, ISC-225, `## Constraints`

### Q3 · Where does the section colour show?

- Offered: orientation only — nav marker, page header, panel edge (recommended) | orientation plus
  the section's primary CTA in the section hue | the nav marker alone
- Chosen: orientation only; primary CTAs keep one colour app-wide
- Landed in: ISC-229, ISC-230, `## Constraints`

### Q4 · Fonts and rhythm: what does "flotter" mean, and do the three fonts stay?

- Offered: the fonts stay and the rhythm gets denser and higher in contrast (recommended) | the
  fonts stay and the rhythm gets airier | new fonts and a new rhythm
- Chosen: new fonts and a new rhythm; the rhythm reading "denser and punchier" carries over from the
  recommended option and stands as a mark
- Landed in: ISC-234, ISC-237, `## Not yet specified` (the pairing)

Read from the repo, not asked: the toast's "Open" is `btn btn-ghost btn-xs` inside `alert alert-soft
alert-success` (`layout/toast-stack/toast-stack.html:32`), and 27 of 49 buttons are the same ghost,
which is why the example fails; route `data` already reaches the shell through `AppShell.leaf()` and
Angular's router inherits it to child routes, so the section attribute is a computed signal and not a
new service; the brand mark is already a mask filled with `--color-primary`, so a single-colour SVG
keeps the mechanism; `src/test-setup.ts` stubs `getContext` to `null`, so no jsdom spec can measure a
colour and the contrast gate has to be a browser tier; `@angular/build:unit-test` accepts `browsers`,
`include`, `exclude` and `setupFiles`, so the tier needs no second runner; `frontend/CLAUDE.md`
stands at 10,740 of 12,000 characters; the accent already leaks into the wordmark, the syntax
highlighting, the "answered" chart series and the favicon spout; the score figure at ~14 px sits at
3.05:1 today; the four proposed `@fontsource-variable` packages exist at 5.3.0.

## Round 1b — draft marks, 2026-09-23

The marks in `spec.md` are the fog lines: font pairing, signal hue, section hues, the secondary
tier's colour, the state marker for an active filter, which surfaces take the section edge, the
offer detail's colour under two parents, the density target, whether the browser tier joins `check`,
and the mark's design. None changes the build structurally, so none was asked; all are resolved at the
palette review in Stage 1 or in the plan's approach lock.

## Round 2 — before the plan, 2026-09-23

### Q1 · In which order is 003 built?

- Offered: harness first, then the palette and three mark candidates in one review, then tiers,
  section colour, type and rhythm, brand, records, each stage confirmed in a real browser
  (recommended) | tiers and section wiring first on the old palette, the palette last | everything in
  one pass, one review at the end
- Chosen: harness first, then palette and mark as one review
- Landed in: plan.md § Approach

Read from the repo, not asked: `@angular/build:unit-test` accepts `browsers`, `include`, `exclude`
and `setupFiles`, so the browser tier is a target and a setup file rather than a second runner;
Chromium builds already sit in the Playwright cache on this machine; three specs select
`.btn-primary`, so the primary tier keeps that literal class; the four `.panel` rules in analytics,
dashboard, offer-detail and rules CSS are identical, which is what `primitives.css` collapses;
`brand-mark.spec.ts:39` pins the mask's width, which is the one functional-spec line the brand stage
touches; the working tree carries the operator's uncommitted edits to `README.md`, `CLAUDE.md` and
`docs/*`, so Stage 6 merges rather than overwrites.

## Round 3 — the Stage 1 review, 2026-09-24

The palette went live on the dev server as it was written, so the operator saw it before the
sitting. Three things came back mid-turn, verbatim:

- "wir müssen gleich nochmal über die farben reden. das sieht alles super aus, ähnelt aber zu
  sehr einer app eines kollegen - wegen den blau-schema." — the first light theme was Indigo &
  Magenta.
- "das darkthem sieht super aus und sollte so bleiben, und als default eingeschaltet sein."
- "\"lead ring\" ist super" — over the three-candidate mark sheet.

### Q1 · Which direction for the light primary, away from blue?

- Offered: Teal & Magenta (recommended) | Ink & Magenta | Plum & Magenta
- Chosen: Teal & Magenta, "das grau im dark theme ein wenig heller, aber nicht viel"
- Landed in: `styles.css` light theme (primary `oklch(52% 0.088 185)`, at the sRGB ceiling for a
  teal of that lightness), dark surfaces and muted one and a half steps lighter; spec § Decisions

Not asked: the dark default is a behaviour change the operator ordered, so `theme.store.spec.ts`
and `chart-theme.spec.ts` change with it and ISC-233's allowlist names them.

## Still open

