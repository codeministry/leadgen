---
task: "Anchor navigation on the long screens"
slug: 007-anchor-navigation
spec_type: feature
isa_master: ../../ISA.md
isa_feature: F35
constitution: ../constitution.md
phase: complete
progress: 8/8
started: 2026-09-24T03:58:00Z
updated: 2026-09-24T00:55:00Z
principal_stated_goal: "und die Seiten \"Analytics Sources Review Rules\" sollten alle eine linke maginal spalte mit anker-navigation erhalten, zur besseren übersicht und handling"
principal_stated_goal_source: prompt
principal_stated_goal_signal: 3
principal_stated_goal_locked: 2026-09-24T03:50:00Z
context_sufficient: true
interview_invoked: false
context_log: context.md
---

<!-- SPEC — a derived view of ../../ISA.md (feature F35). Claim IDs belong to the master.
     Sync: Skill("Spec", "sync 007-anchor-navigation"). Never edit the master from this file.
     principal_stated_goal is the one German string in this folder: the format keeps the principal's
     words byte for byte, and they carry no value the constitution keeps out of specs/. -->

# 007 — Anchor navigation on the long screens

## Problem

Four screens are long pages of stacked panels. Analytics has five, rules four, and the sources
and review screens open a document or a panel beside a list, each with its own headings. Nothing
on any of them says what is on the page or takes the reader to a section; the only way down is
the scrollbar, and the only way to know where you are is to read the heading you happen to be
under. The operator asked for a left margin column with anchors on all four, for overview and
handling.

## Vision

He opens analytics and a narrow column on the left lists what the page holds: intake, runs,
market, applications, scores. He clicks "scores" and the page lands on it, under the sticky
header, with the column's marker now on "scores"; as he scrolls back up, the marker follows. On
the phone the column is a row of chips under the title and the page never gets wider. It is the
same column on rules, on sources and on review, in the screen's own section colour, and it does
not exist on the shortlist or the pipeline, which already have columns of their own.

## Out of Scope

- **A table of contents for the offer detail** or any split view.
- **Scroll-spy through the URL alone**: the hash follows a click, the marker follows the scroll.
- **A select or a menu on the phone**: a chip row keeps every section one tap away.
- **Reordering or renaming the sections** of the four screens.

## Constraints

- **One component** (`shared/anchor-rail`), which takes the sections as an input and knows no
  screen; `shared/` imports nothing from above (FE-LAYER-01..04).
- **The section colour is the only colour** the rail's marker reads; ISC-230's allowlist grows by
  one file, on purpose, and stays exact.
- **Layout tokens, not literals**: `--lg-anchor-w` beside `--lg-list-w` in `tokens.css`, the sticky
  offset from `--lg-sticky-top`.
- **Native semantics before ARIA** (DS-APP-37): a `<nav>` with an accessible name, real anchors,
  `aria-current="location"` for the section in view.
- **Focus lands in the section** (DS-APP-35): a section navigation does not return focus.
- **Anything lifecycle-dependent is verified live** (DS-APP-43): the observer's hand-over is read
  through `VerifyViewport.ts`, never a backgrounded tab.
- **No sideways scroll** at 320 and 375 px on any of the four screens.

## Goal

Analytics, sources, review and rules each get a left margin column with in-page anchor links to
their sections, sticky under the header, marking the section in view, collapsing to a chip row
under the page header on a phone, coloured by the screen's section colour and by nothing else,
through one shared component the split views never render.

## Features

### F35 · Anchor navigation on the long screens

**Why:** Analytics, sources, review and rules are long pages of stacked panels with nothing that
says what is on them or takes the reader to a section; the operator asked for a left margin
column with anchors on all four, for overview and handling.

- [x] ISC-275: One component, `shared/anchor-rail`, renders a screen's sections as a vertical `<nav>` of in-page links in a left column of width `--lg-anchor-w`, sticky at `--lg-sticky-top`; it takes the sections (id and catalog key) as an input and holds no screen knowledge of its own.
- [x] ISC-276: Analytics, sources and review each declare their sections and render the rail; every section heading carries the id its link names, a click lands the heading under the sticky header through `scroll-margin-top`, and the URL hash follows. (after: ISC-275)
- [x] ISC-277: The link of the section in view carries `aria-current="location"`, driven by an `IntersectionObserver`, and the hand-over between two sections is verified in a browser whose rendering lifecycle is live. (after: ISC-276)
- [x] ISC-278: Below 48rem the column is gone and the rail is a horizontal, scrollable row of chips under the page header; no screen scrolls the page sideways at 320 or 375 px. (after: ISC-276)
- [x] ISC-279: The rail's active marker reads `--lg-section` and no other colour; the ISC-230 allowlist grows by exactly `anchor-rail.css` and stays exact. (after: ISC-275)
- [x] ISC-280: The rail is a `<nav>` with an accessible name, its links are anchors with the section's text, and a section navigation moves focus to the section's heading and never back to the rail. (after: ISC-276)
- [x] ISC-281: Anti: the shortlist or the pipeline renders a rail; a screen that declares no sections renders none. (after: ISC-275)
- [x] ISC-282: `docs/decisions/frontend-design-system.md` carries the rail's decisions, `CHANGELOG.md` § Unreleased names it, and `WorkingNotesStaySmallTest` stays green. (after: ISC-276)

## Test Strategy

| isc | type | check | threshold | tool | anchors_to |
|---|---|---|---|---|---|
| ISC-275 | bun-test | render `lg-anchor-rail` with three sections; `rg -n 'lg-anchor-w\|lg-sticky-top' anchor-rail.css` | a `nav` with three anchors to `#id`, `position: sticky`; both tokens read | Vitest, rg | `anchor-rail.spec.ts`, `anchor-rail.css` |
| ISC-276 | bun-test | each of the three screen specs (analytics, sources, review): every declared section id exists as a heading with `tabindex="-1"` and the `lg-anchor-target` class, whose `scroll-margin-top` is one rule in `primitives.css`; the hash and the landing measured live | four screens green; hash set, path kept, panel edge under the header | Vitest | `analytics.spec.ts`, `sources.spec.ts`, `review.spec.ts`, `rules.spec.ts` |
| ISC-277 | manual | `VerifyViewport.ts`: scroll the analytics screen past its second section and read `aria-current` | moves from the first link to the second | Interceptor `VerifyViewport.ts` | `anchor-rail.ts` |
| ISC-278 | manual | `VerifyViewport.ts` at 320 and 375 on the four screens: rail placement and `scrollWidth` | chip row under the header; `scrollWidth == clientWidth` | Interceptor `VerifyViewport.ts` | `anchor-rail.css` |
| ISC-279 | bash | `rg -l 'var\(--lg-section\)' frontend/src` | exactly `app-nav.css`, `page-header.css`, `primitives.css`, `anchor-rail.css` | rg | `anchor-rail.css` |
| ISC-280 | bun-test | render the rail, activate a link, read `document.activeElement` | the section heading (`tabindex="-1"`), not the rail | Vitest | `anchor-rail.spec.ts` |
| ISC-281 | bash | `rg -c 'lg-anchor-rail' frontend/src/app/features/shortlist frontend/src/app/features/pipeline`; render the rail with no sections | 0; nothing rendered | rg, Vitest | `anchor-rail.spec.ts` |
| ISC-282 | bun-test | `WorkingNotesStaySmallTest`; `rg -n -i 'anchor' docs/decisions CHANGELOG.md` | green; ≥ 1 each | JUnit, rg | `CHANGELOG.md`, `frontend-design-system.md` |

## Decisions

- **2026-09-24 — One component, declared sections.** The rail could read the headings out of the
  DOM; it takes a declared list instead, because a heading inside a panel that only renders once
  data arrives would appear late and reorder the rail, and because the catalog key is what the
  link should say, not the heading's rendered text.
- **2026-09-24 — Marks, with their reasoned defaults.** ⟨?: `--lg-anchor-w` at 11rem — wide enough
  for "applications" in German, narrow enough beside a 36rem list⟩ ⟨?: a chip row below 48rem,
  not a select — one tap per section, and it is the bottom bar's idiom already⟩ ⟨?: focus lands
  on the heading through `tabindex="-1"`, which is what lets a screen reader read the section
  it arrived in⟩.
- **2026-09-24 — A router link with a fragment, not `href="#id"`.** The first cut used bare
  anchors and the live probe reported "target navigated": the app's `<base href="/">` is what a
  fragment-only URL resolves against, so `#scores` opened the dashboard with a hash. Each link is
  `[routerLink]="[]"` with `[fragment]` and `queryParamsHandling="preserve"`, which keeps the
  path and the query and sets the hash; the router does not scroll (anchor scrolling is off, and
  its own would ignore the sticky header), so the click handler scrolls the heading into view —
  which honours the scroll margin — and focuses it. The trap is one line in `frontend/CLAUDE.md`.
- **2026-09-24 — The rail wraps the screen.** Rather than a layout primitive the four stylesheets
  would each declare, the rail projects the screen's content as its second column; `:host` is the
  grid, `:host(.bare)` a block when there are no sections, and the chip row below 48rem is the
  same component under a media query. One place to get sticky right.
- **2026-09-24 — Sections follow the data, not the screen.** Analytics and rules declare their
  sections once the view has arrived, sources adds the panel's two headings once the panel has
  rendered (`store.detail()` for the open id), so the rail never links to a heading that is not
  there and the observer, wired in `afterRenderEffect`, finds every target it is given.
- **2026-09-24 — Two screens gained screen-reader-only headings.** The sources table's title is
  the page header and the review's panes were `aria-label`led; both now carry `sr-only` `h2`s
  with the ids, which also turns the panes' labels into `aria-labelledby`. On the review below
  72rem with a document open, the queue and the upload are hidden and their chips lead nowhere
  visible; ⟨?: left as is — a chip that scrolls to a hidden pane is inert, not wrong⟩.
- **2026-09-24 — Deep links land.** `/analytics#scores` scrolls and focuses the section once its
  target exists, through the same `land()` the click uses; the hash is read once at construction.
- **2026-09-24 — Two findings fixed on the way.** `analytics.css` and `rules.css` still carried the
  pre-primitive `.lg-panel` rule, which in a component stylesheet beats the layered primitive: the
  rules panels had no section edge and both screens had their own padding (measured live:
  1px/18.75px against the primitive's 3px/15px). Removed; `offer-detail.css` carries the same copy
  and is left for its own change. And the analytics tile read `dashboard.unitOffers`, a key spec
  006 pruned; it is `analytics.unitOffers` now.
- **2026-09-24 — Marks resolved.** `--lg-anchor-w` at 11rem; a chip row below 48rem, not a select;
  focus lands on the heading through `tabindex="-1"`.
- **2026-09-24 — A click glides, a deep link jumps.** The operator, on accepting the rail: "weiches
  Scrollen wäre besser". `scrollIntoView` with `behavior: 'smooth'` on a click and `'auto'` on a
  page opened with a hash, both `'auto'` under `prefers-reduced-motion`; the duration is the
  browser's own, so DS-APP-41 has nothing to token.
- **2026-09-24 — refined: ISC-276 narrowed to analytics, sources and review.** Spec 008 turns the rules screen into a split view (ISC-287) whose stage rail is its navigation, and ISC-292 forbids an anchor rail there; the operator chose that over two navigations on one page. The claim keeps its ID and its close, because its evidence covers the three screens; the rules part of its probe moves to `rules.spec.ts` under ISC-293. The rail 007 built on rules is removed by 008's rewrite, not by 007.

## Verification

- ISC-275 — anchor-rail.spec 'renders a named nav with one in-page anchor per section' (three anchors ending in `#intake`, `#runs`, `#scores`, `aria-label` from the input); `rg -n 'lg-anchor-w|lg-sticky-top' anchor-rail.css` = lines 6 and 18; `--lg-anchor-w: 11rem` in `tokens.css`; live at 1440 the nav is 165px wide, `position: sticky`, top 166 under the header at 143 (2026-09-24)
- ISC-276 — analytics.spec 'declares its sections and renders a focusable heading for each, in that order' (6), rules.spec (3, and the prompts as a fourth when a model has prompts), sources.spec 'declares the table as a section, and an open panel adds its two headings', review.spec 'declares its three sections…'; 24/24 green; live on analytics: click on the scores link → `location.hash` `#scores`, path `/analytics` kept, panel top 82 under the header bottom 53 (2026-09-24)
- ISC-279 — `rg -l 'var\(--lg-section\)' frontend/src` = `app-nav.css`, `anchor-rail.css`, `page-header.css`, `primitives.css`; the marker is a 2px inline-start border (column) or the chip's ring (row), the text stays base-content (2026-09-24)
- ISC-280 — anchor-rail.spec 'moves focus to the heading a link lands on, and never back to the rail' (`document.activeElement` is `h2#runs`, the link carries `aria-current="location"`); live: `document.activeElement.id` = `scores` after the click (2026-09-24)
- ISC-281 — `rg -c 'lg-anchor-rail' features/shortlist features/pipeline` = no match (exit 1); anchor-rail.spec 'renders nothing when a screen declares no sections, and keeps the content' (no `nav`, host `.bare`, the three headings still projected) (2026-09-24)
- ISC-277 — manual, the operator's word on 2026-09-24 over the live screens: "anker passt"; the measurement behind it: `VerifyViewport.ts` on analytics at 1440×900, live lifecycle: `aria-current` on `#intake` at the top, on `#market` after scrolling its heading to 120px, back on `#intake` at the top, on `#scores` after the click; 1.8 s end to end (2026-09-24)
- ISC-278 — manual, the same word; the measurement behind it: `VerifyViewport.ts` at 320 and 375 on analytics, rules, sources (panel open) and review: the nav is `position: static`, its list `flex-direction: row`, 15px from the left edge under the page header (top 158–335 against header bottoms 136–153), `scrollWidth == clientWidth` at every width; at 1440 all four are `sticky` columns (2026-09-24)
- ISC-282 — `rg -c -i 'anchor'`: `frontend-design-system.md` 9, `CHANGELOG.md` 4; `frontend/CLAUDE.md` gains the base-href trap (11,373 of 12,000); `WorkingNotesStaySmallTest` green in `./gradlew check` (2026-09-24)
