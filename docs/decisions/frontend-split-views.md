# The split views and the write path

One list on the left, one thing being read on the right, and the board that writes back.

These are working notes moved out of `CLAUDE.md` so the always-loaded file stays small.
Every paragraph here was paid for once; none of it is a summary.

## The board and the write path

`frontend/…/core/store/applications.store.ts` plus `features/pipeline/` and the panel in
`features/offer-detail/`. The first screen in this app that writes.

- **The lanes come from `/api/v1/applications/lanes`, not from a constant.** Eleven states
  across five lanes is a decision the enum already makes; a second copy in the browser
  disagrees with it the first time a state is added — visibly on the board, invisibly in
  the code.
- **`shared/` does not know what an application is**, so the picker takes plain
  `{ value, label }` options and emits a string. The layering rule is the reason, and the
  cast back to `ApplicationStatus` is safe for the same reason: the options came from the
  feature that owns the type.
- **The picker's options are cut into `<optgroup>`s by an optional `group` on the option,
  and the runs are consecutive rather than collected by name.** Eleven flat states are a
  list to read; five headings are a shape to recognise, and they are the same five the board
  draws, because both come from `statusChoices` and that comes from the lanes the server
  states. A plain string keeps the layering intact — `shared/` knows that consecutive options
  naming the same group belong together and nothing else. Consecutive matters: the order is
  the path an application usually takes, and regrouping by name would move whatever sat
  between two runs of the same heading. An option with no `group` starts an ungrouped run, so
  a caller that sets none gets exactly the flat list this control had before.
- **Nothing is styled on the `optgroup`.** Measured live, the browser already gives the
  heading `font-weight: 500` against the options' 400 and indents them by 7.5px. More CSS at
  `<option>`/`<optgroup>` is the kind of change that reads as work and does nothing: macOS
  paints a native select's popup itself and honours almost none of it.
- **Bind the selection on the option, not with `[value]` on the select.** `[value]`
  depends on the options existing when it is written; losing that race leaves the first
  option showing, so a card reads "New" while it stands in the Out lane. No error, and
  picking the state it is already in looks like nothing happening. Found when a status
  badge on the card still said SENT beside it — the badge is gone now, so the picker is
  the only place the state is written and this rule is the only thing keeping it honest.
- **The row is replaced with the server's answer, never with what was asked for.** The
  service dates a send itself and drops a follow-up on closing, so a locally patched row
  would disagree with the database until the next reload.
- **Clearing the follow-up is a button, not an empty field.** Emptying a native date input
  means deleting each segment in turn, and `clearFollowUp` is unreachable in practice
  without it — measured in the browser, not reasoned about.
- **The follow-up tile shows an em dash when the board did not load.** A zero and an
  unreachable API look identical on a tile, and that tile is the reason the dates get
  entered at all.
- **`saving` names the application in flight, not a boolean.** One card greys out; the
  rest stay usable.
- **A state is the drop target, never a lane.** Five lanes hold eleven states and four of them
  hold more than one; `closed` begins at `WON`, so a card let go of on the lane would be marked
  won on the strength of the enum's declaration order. Each lane therefore renders its states as
  their own `cdkDropList`. The zones carry a `min-height` while one is being aimed at, because
  an empty state with no children has no box to let go of a card over, and the lane's "nothing
  here" line moved up under the lane head where it is said once instead of five times.
- **At rest a lane is one list, and the states are its order rather than its headings.** The
  groups are laid out at every moment — they have to be, see the reveal below — but an empty
  state is `display: none` and a heading only appears while a card is in the air. "Packaged"
  under a lane already called "Prepared" is the same word twice anyway; the rest are a
  structure the reader does not need until there is something to aim at.
- **Sorting inside a zone is off.** There is no order to persist: the board query decides the
  order within a state, so a dropped position would be honoured until the next load and then
  silently revert. `cdkDropListSortingDisabled` says that out loud; leaving it on would have
  been a promise the server never made.
- **The card moves before the answer is back, and the row it moved from is kept.** A drag whose
  card stays put for a round trip reads as a drag that did not take. Only the **status** is
  patched locally, though — the service dates a send itself and drops the follow-up on closing,
  so anything else written here would be a guess standing next to the real row until `updated`
  replaces it. `changeFailed` grew an `id` for exactly this: a rollback has to name the card,
  and `pending` is keyed by id rather than a single slot because the writes are `concatMap` and
  a queued second change would otherwise overwrite the first one's undo. The same edit turned
  the hardcoded English sentence into `error.statusSave` — the template pipes `store.error()`
  through `| transloco`, so that sentence was being looked up as a key, missed, and printed.
- **The grip drags, not the card, and the picker stays.** A `<span cdkDragHandle>` rather than
  a `<button>`: a focusable control that does nothing on Enter promises a keyboard path drag
  does not have, and that path is the picker two rows below. The whole card was tried as the
  drag surface and given up. It costs text selection, it needs `cdkDragStartDelay` on touch or
  a swipe up a lane drags a card instead of scrolling, and it puts the gesture on the same box
  as the stretched title link — where a click after a drag is at least a question. Measured,
  that question has a reassuring answer: a click fires on the nearest common ancestor of the
  `mousedown` and `mouseup` targets, and the release target is the `<ul class="cards">` in both
  cases that matter — a drag into another zone and a drag returned to its exact starting point,
  because the CDK lifts the card out of the flow and the preview takes no pointer events. So
  the common ancestor is the board, never the anchor, and no click needs suppressing. The grip
  is kept anyway, for the other three reasons.
- **The card carries no status badge.** The picker directly above it is the same word, and a
  second copy of a value is a thing that can disagree with it — which is exactly how the
  `[value]` race above was found. What is left on the card is the score, the title, the dates,
  the picker and the agency: five things, each said once.
- **The card takes a hover, the border only.** The same treatment and the same `color-mix` the
  shortlist's card carries, so a board card and a list card behave alike; a wash would have to
  be told apart from the selected card's, and hover is not a statement about the offer. The
  grip brightens with it: it is an affordance for the hand already on that card, not a sixth
  thing to read on a surface scanned twenty at a time.
- **The state zones open on `pointerdown`, not on `cdkDragStarted`, and the difference is a
  defect.** The CDK caches every container's rectangle when the drag sequence starts — at the
  first move past the threshold — and never asks again. Zones revealed after that are measured
  collapsed: a drop over one produced **no request at all**, while the identical drop with the
  zones already open wrote `{"status":"LOST"}`. Both measured in the browser, which is the only
  place the question can be asked. The press is unconditionally before the first move, so the
  geometry the CDK reads is the geometry on the screen. Bound to the grip and not to the card,
  so pressing a card to read it never opens them: only a hand already on the drag affordance
  does, which is also what removes the flash the whole-card variant had on every click.
- **The class is written onto the element, not bound with `[class.picking]`.** A signal binding
  is applied by change detection a frame later, and a frame later is precisely the race the
  previous point is about. One owner, one moment, synchronous — and the spec asserts it without
  a `detectChanges()` in between, which is what would catch a well-meaning rewrite into a
  binding.
- **The drag preview is styled in `styles.css` and the rest in `pipeline.css`.** The CDK appends
  the preview to `<body>`, where no component-scoped rule reaches it, and leaving it in the
  document rather than `[cdkDragPreviewContainer]="'parent'"` is what stops `.board-col`'s
  `overflow: auto` from clipping the card halfway across the board. The zone highlight and the
  placeholder land on elements inside the template and stay with the feature. Petrol throughout,
  never ochre: a drop target is not a statement about the offer.
- **`.board-col` needs `cdkScrollable` or a drag towards the edge scrolls nothing.** The CDK
  auto-scrolls only containers it has been told about, and this one is deliberately a single
  scroller for both axes.
- **jsdom cannot produce a CDK drag, so the spec emits the drop on the directive** the template
  binds to, rather than calling the component's method. The assertion that matters is the
  request, and that half is real: a drop in another zone PATCHes, a drop in the zone the card
  came from asks for nothing — the server records no event row for a status that did not change.
- **A DOM-render screenshot is not proof of what the browser paints.** It serialises and
  re-renders, which drops DOM properties that have no attribute (a `<select>`'s
  selection) and some component CSS on SVG children (the score ring). Read the
  accessibility tree for state — `interceptor read` shows `combobox … value="SENT"` —
  and treat a capture as evidence about layout, not about widget state.

## The split views

`features/shortlist/`, `features/pipeline/` and `features/review/`, with `layout/app-shell/`
underneath all three. One list on the left, one thing being read on the right, and neither column scrolls the other.

- **The board is bounded; the shortlist and the review hand their reading column to the document.** A route asks to be
  bounded with
  `data: { fill: true }` and `AppShell` reads that exactly where it reads `data.measure`, because the element that has
  to stop scrolling is an ancestor of the screen. `.shell.fill` takes a real `height: 100dvh`: `min-height` alone is not
  a height, the flex chain resolves against it only while the content is shorter, and a long list simply grows the shell
  past the viewport, so the page scrolls and the panes never do. Measured that way before the `height` was added. The
  board keeps it, because five lanes plus a reading column dividing a *growing* page is two gestures with two owners and
  `.board-col` is deliberately one scroller for both axes. The other two gave it up: the advert and the uploaded
  document are the prose surfaces here, a document scroll is what a reader's hands already know, and an inner scroller
  on a phone buys nothing at all. On both the list column is `position: sticky` with a scroller of its own while the
  reading column simply grows. On the review that also means `.page-head` — the title and the drop zone — scrolls away,
  which is right: uploading is a one-off and correcting is per document. `align-items: start` on the grid is
  load-bearing and looks cosmetic — a grid item stretched to the row height has no room to move in and
  `position: sticky` on it silently does nothing. `100svh` and not `100dvh` for its max-height: the dynamic unit changes
  as a mobile browser shows and hides its chrome, which would reflow the column on every gesture.
- **The sentinel's root is conditional, and that is the regression this model can cause.** Above the breakpoint the root
  is the list pane, which is a scroller. Below it the pane's `overflow` is `visible` and the root is `null`, the
  window — an `overflow` box that clips nothing is still a valid `IntersectionObserver` root, and against an unclipped
  root the sentinel intersects on the first frame and pages the whole archive without anybody scrolling. Measured on the
  archive, 2,197 rows: at 1440 the document scrolled to its end leaves 50 entries and the list pane's own scroll brings
  the next 50; at 1151 the document's scroll brings them. `bothColumns()` is the same `matchMedia` signal the
  auto-selection reads, so the breakpoint is still stated once per side.
- **The three claims that had no test now have one each, and one of them is only half testable.**
  `pipeline.spec.ts` drives the real router: it opens `/pipeline`, navigates to `/pipeline/7` and asserts the board is
  *not* fetched again, which is the child-route reuse the pattern exists for. The picker's is split by what jsdom can
  answer — the CSS half (`position: relative; z-index: 1` above the stretched link) is asserted as the structure it
  depends on, that the picker is not a descendant of the anchor, and the behavioural half by picking a status and
  asserting a PATCH with the URL unchanged. `review.spec.ts` covers confirm and reject: both end the document, so both
  have to drop the name from the query string, and the navigation settles a microtask after the call, which is why the
  spec awaits `whenStable()` before it reads `router.url`. The shortlist's conditional sentinel root has no spec of its
  own on purpose: jsdom has no `IntersectionObserver`, `LoadMore` guards on exactly that, and `load-more.spec.ts`
  already covers the conditional-root shape.
- **The detail's reset moved from the pane to the document.** The right column has no scroller left, so what has to go
  back to the top when the next offer opens is `scrollingElement`. The list column is pinned, so the reader keeps it
  while that happens — which is also why pressing `j` deep inside a long advert is not the yank it would otherwise be:
  the list never left the screen, and the page returns to the top of the newly selected ad on purpose.
- **The board's reading column exists only while something is being read, and its lanes divide the pane at every
  width.** Reserved, the column was 30rem the board did not have with nothing in it — at 1440px the board sat in two
  thirds of the screen, scrolling its five lanes sideways, next to an empty panel. Measured after: 1178px of board and
  225px lanes with nothing open, 709px and the sideways scroll once an offer is. The lanes were also pinned to 15rem
  below the split's breakpoint, which made the board scroll at widths where five stretched lanes still fit; the 14rem
  minimum in `minmax()` is what makes the pane scroll when they genuinely do not.
- **One cap, `--lg-shell-max`, and the header row takes it too.** The measure used to be left-aligned against the nav
  rail, which was the shared left edge every screen started at; with the rail gone there is no such edge, and
  left-aligned the surplus on a wide monitor is simply ragged on the right of every screen. Centring it alone was not
  enough, because the header stayed full-bleed: at 2560px its ink totalled about 1240px and the remaining ~1300px was
  empty bar, next to content sitting in a centred column. Chrome and content were two layouts on one page. Now
  `.header-row` caps and centres against the same bound, **plus both gutters** — the page's gutter lives on `.content`
  and sits *outside* the capped `.measure`, while the row's lives inside it, so at the bare 104rem the two boxes
  coincided and the ink did not: the brand mark began 22px right of the page's first character. Measured live and then
  equal to the pixel at 2560, 1920, 1600, 1440, 1152, 768, 767 and 390, on both edges. The bar itself stays full-bleed —
  the page scrolls *under* it, so a bar that stops short of the window lets content past it at the ends, and below 48rem
  the navigation is a full-bleed fixed bottom bar an inset top bar would contradict. **`--lg-measure-wide` (120rem) was
  deleted because it was unreachable:** both split screens cap their own host at `--lg-split-max`, so above 1560px the
  child cap won and below it the window did, and `.measure.wide` was observationally identical to no cap at all. Same
  class as `remote.accept_unknown` — read, applied, and changing nothing — and it is exactly the number someone would
  have capped the header at. `--lg-measure` and `--lg-split-max` are now aliases of the one bound; `data.measure` has
  two states left, `full` and absent.
- **The list column takes 36rem and the advert gives them up.** The card is what is scanned twenty at a time and it
  carries a title, four meta values and a score; the advert is prose and was the wider of the two by a long way. It
  stays a fixed width — a proportional split re-wraps the card's meta row on every monitor. One token for the shortlist
  and the review both: the review's queue is the same card read the same way. The cost is at the bottom of the
  two-column range, where the reading column is 4rem narrower than it was; at 1024px with the nav rail open it is around
  15rem, which was already too narrow before this change.
- **The board takes the whole window; every other screen takes the shell's bound.** `data.measure` is a string and not a
  boolean even at two states — a `full` flag beside the `wide` flag it used to have is two booleans on one axis, and
  then the stylesheet's order silently decides; the name is also what let the dead third state be found and removed. The
  board has five lanes and a reading column dividing whatever width there is, so every rem a cap withholds is width
  taken off all five, and `.measure.full` is `max-width: none` rather than a bigger number: a cap wide enough for
  today's monitor is wrong on the next one. That is also why there is no `full` token. The header row stays capped above
  it, and that is deliberate: the board's outer edge is a scroll-container edge, not a text edge, so chrome over a
  workspace is a different relationship than chrome over a reading column.
- **Every `min-height: 0` down that chain is load-bearing.** A flex item's default is its content height, which is
  exactly how a "bounded" pane grows the page instead of scrolling, and it looks correct in a screenshot while doing it.
  The chain is
  `.shell.fill` → `.body` → `.content.fill` → `.measure.fill` → the feature's `:host` →
  `.split` → `.pane`.
- **`<router-outlet>` gets `display: none` inside `.measure.fill`.** It is a comment anchor with no box; in a flex
  container it would still take a slot. The routed component is its next sibling and carries the height.
- **The breakpoint is 72rem on all three, and below it the list is hidden rather than overlaid.** It was 80rem, measured
  with the nav rail **open** — the worse of two states no media query could see, because the rail went from 4rem to
  14.5rem on a click with no breakpoint of its own. That rail is gone, so there is one state left and the old number
  defends a layout that no longer exists. The floor it defended is the reading column it produced in the bad state,
  489px. Without the rail that column is `min(V - 45, 1560) - 558.75`, so 72rem yields 548px and 64rem would yield
  420px. Measured after the change, on all three screens: two columns at 1152 and one at 1151, the shortlist and the
  review at 548px of reading column and the board at 638px of lanes beside its fixed 30rem panel; 706px at 1280, where
  the detail's panels now sit two-up. 48rem is where the navigation becomes a bottom bar and stays its own number:
  stacking two structural relayouts on one makes both harder to check.
- **A `rem` in a media query is not a `rem` in a rule, in this repository.** `html` sits at `font-size: 93.75%`, so the
  layout's rem is 15px while a media query resolves against the initial 16px whatever the root says. `72rem` is
  therefore 1152px, and `--lg-list-w: 36rem` is 540px. Comparing the two numbers as if they were the same unit is how a
  breakpoint gets picked for a column width it does not actually produce.
- **The selection is a route, never local state.** The URL is what a deep link, the back button and a click all agree
  on, and a second copy in a signal disagrees with it the first time one of the three is used. Read from
  `route.snapshot.firstChild` with `NavigationEnd`
  as the reason to look again — the shape `AppShell` already uses.
- **Two of the three use a child route; the review uses a query parameter, and that is not a style.** The shortlist and
  the board have a real component on the right (`OfferDetail`), so a child route has something to render. The review's
  document is already in the store the screen reads, so a child route would render a component whose only job is to look
  up by name what the parent is holding — and Angular refuses a componentless leaf route outright with `NG04014`, which
  is the framework making the same point.
- **A child route and not a second flat route on the same component.** Two `Route` objects are two configurations, so
  the default reuse strategy destroys the list on the first click:
  it refetches, `entries` falls back to page one, and the scroll position is gone. A single route cannot express an
  optional path parameter.
- **`queryParamsHandling: 'preserve'` on every card link.** Without it the first click drops the query string, the list
  reloads unfiltered, and it reads as a store bug rather than as a missing attribute.
- **The whole card is the link, through one stretched anchor.** A click handler on the article would need its own
  keyboard path to satisfy `click-events-have-key-events` and
  `interactive-supports-focus`, and it would be a second way to the same route. Two costs come with it: text in the card
  can no longer be selected with the mouse, and **any control on the card needs `position: relative; z-index: 1`** or it
  stops being clickable — which is what the board's status picker carries.
- **Selection is petrol, never the accent.** Ochre means one thing in this application: this survived the filter. The
  selected row takes the primary border, `--lg-selected-surface` and a 3px edge marker, the same vocabulary the nav rail
  uses for "you are here". Hover tints the border only, so hovering a selected card never reads as deselecting it.
- **`ShortlistStore` keeps two loading/error pairs.** `listLoading`/`listError` and
  `detailLoading`/`detailError`. They used to be one pair, the shortlist template branches on the error first, and a
  single failed detail fetch therefore blanked the whole list beside it. The same reason `rescoreError` was already kept
  apart.
- **`LoadMore` takes a `root`.** Its `IntersectionObserver` measured against the window; in a pane that scrolls on its
  own the sentinel then intersects on the first frame and on every frame after, and pages the entire archive without
  anybody scrolling. `rootMargin` is the other half: with the window as root and a scroller in between, the margin
  expands the window's rectangle while the pane still clips unmargined. The root is a template reference on the ancestor
  `<section>`, not a `viewChild`, so it is a real element on the first pass.
- **The fill layout applies at every width, which is what makes that root safe.** One scroll model everywhere, no
  `matchMedia` in TypeScript duplicating a CSS breakpoint, and nothing that changes behaviour when a window is dragged
  across the split's own breakpoint.
- **The detail's panel grid is a container query, and the container is the pane.**
  `@container detail (width < 44rem)`. The viewport cannot answer for that column: the nav rail expands from 4rem to
  14.5rem **with no media query at all**, so at 1280px the detail is 47.5rem collapsed and 37rem open and no viewport
  number is right for both. The
  `container-type` sits on `.detail-pane` and deliberately not on `lg-offer-detail`:
  containment must not land on an element whose height has to grow, and the pane's height comes from the flex chain
  rather than from its content.
- **Keyboard navigation is bound to the list pane, not to the document.** `keydown` bubbles from the focused card link,
  so the handler fires only while the focus is in the list — which is what lets `j` stay a letter in the search field
  and leaves the arrow keys scrolling the advert while the reader is in the detail column, with no target sniffing
  anywhere. The pane carries `tabindex="0"` because a scrollable region has to be reachable by keyboard at all. Past the
  last loaded entry the key asks for the next page and stays put: the list is keyset-paged, so "next" beyond what is
  loaded does not exist yet.
- **`a` takes the open offer off the side being read, and asks first only when that could cost a document.** It
  archives from the working list and restores from the archive, by the rule the detail's button already uses, and on
  the same pane so it stays a letter everywhere else. Lowercase only: the handler's bail on every modifier stays, so a
  Shift+A typed by accident does nothing. An offer with a package opens a confirmation instead, because archiving
  deletes a package nobody sent and a restore resets the application to NEW without rebuilding it; the browser cannot
  tell a sent application from an unsent one, so a package on disk stands in for both and asks once too often rather
  than once too rarely. After the write the neighbour opens — the one below, or above when it was the last — decided
  before the row goes, and only on success, so a failed write leaves the reader on the offer beside its error.
- **`applicationEvents.opened()` stays in `OfferDetail.ngOnInit`, and the split made it cheaper.** The component is now
  created once and reused across ids, so the whole board is fetched once per session instead of once per offer opened.
  The accepted consequence: a status changed elsewhere mid-session is not picked up.
- **`/offers/:id` is a redirect into `/shortlist/:id`.** A `:name` in `redirectTo` is substituted from the matched
  segments, so old links and bookmarks keep working, and there is exactly one detail view in the code afterwards.
- **`lg-page-header` takes a `heading` of `h1` or `h2`.** The detail column renders inside another screen; a second `h1`
  claimed to be the page while the screen's own title stood beside it, and shouted at 2rem next to a scan column.
- **The shortlist's heading sits above both columns, like every other screen's.** It used to live inside the left
  column, on the argument that it belongs to the list; it read as a column label rather than as the name of the screen,
  and it was the one screen whose title was somewhere else. The count and the archive toggle came up with it — the count
  is read on every filter change and the toggle decides which *set* the screen is showing, which is a statement about
  the screen. The filters stayed down in the column: they filter the list and nothing else, and a search field spanning
  the page while acting on a 36rem column is a false affordance.
- **The filter bar is three kinds of control and three treatments.** A *query* takes its own
  row; an *order* is not a filter at all and says so by being a trigger of its own rather than
  the third select in a row of them; five *facets* live behind one trigger and show as chips
  when they are on. `archived` is none of the three — it is which *set* is on screen, and it
  is up in the header with the count. It replaces ten controls in four equal rows, which
  spent about two thirds of a card permanently while displaying nothing: every row of filters
  is a row of list, because this column is sticky with a scroller of its own. Two rows at
  rest, a third only while something is set.
- **Chips exist for exactly what the popover hides, and that is what keeps the badge honest.**
  The search text is in its own field and the band is lit in its own group three centimetres
  above; a chip for a control that is already showing its state is a second copy of it. The
  count on the trigger is the chip list's own length, so the two can never disagree. A portal
  is one chip each rather than one saying "3 portals", because the point of a chip is that it
  can be removed on its own — and the whole chip is the button, with the ✕ as a decorative
  glyph: a 12px icon inside a 28px chip cannot be a 24×24 target and the chip trivially is.
  Petrol on the selection wash, never ochre; ochre means "this survived the filter", not "a
  filter is on".
- **The two popovers are placed from the trigger's own rect, not from arithmetic.** A popover
  is in the top layer, whose containing block is the viewport whatever `position` says. The
  header's settings panel can compute its offset because its trigger is pinned to the shell's
  right edge; a filter trigger sits in a column whose position depends on the width, on the
  language and on what wrapped. `shared/popover/anchor-for.ts` writes two custom properties
  from a measured rect on `click` — before the activation behaviour opens the panel — and the
  stylesheets clamp with `min()` against their own width. **Custom properties and not
  `left`/`top`**, because below 48rem both panels are bottom sheets and a real property
  written inline would beat the media query that makes them one. Both stylesheets carry the
  `display`-on-`:popover-open` and `inset: auto` traps the header already shipped once.
- **Saved views sit in the header, not in the filter column.** A view is a name and a query
  string, so it carries the order and which set is being read as well — a statement about the
  screen rather than about the list. It is also what keeps the filter row from wrapping: the
  band group, the sort trigger and the facet trigger already want about 500 of the column's
  540px. The URL stays the truth: applying one replaces the query string and nothing records
  "which view is showing", because the reader changes a filter a second later and any such
  flag would then be a lie. `core/filter-views/` is the theme store's shape for the theme
  store's reason — the I/O is localStorage, so it is `withHooks` plus an `effect`, every
  access wrapped, and one corrupt entry costs one view rather than the list.
- **Four defects went with the rebuild, and each was visible only in the bar.** `onInput`
  wrote the URL on every keystroke, so a typed word was ten navigations, ten requests and ten
  history entries — 250ms and `replaceUrl` now, which is also the precondition for the count
  becoming this screen's one `role="status"`: announced per keystroke it would chatter over
  the typing it reports on. The band group's three `aria-pressed` buttons became native
  radios, because exactly one is always on and `aria-pressed` states that they are
  independent. And the ✕ beside the search cleared the whole query string, `archived`
  included, so it took a reader out of the archive; it clears the search alone, and *Clear
  all* is the last chip — where what is being cleared can be seen — and leaves `sort` and
  `archived` standing, because an order is not a filter and a set is not one either.
- **The bar is capped to the column only while there are two of them.** Below 72rem the cards
  under it run the whole measure, so a 540px bar over 1100px cards is a cap defending a layout
  that no longer exists. What keeps its cap there is the search field alone, at 32rem: it is
  the one control that would read as a runway.
- **The offer card carries no description teaser.** Two clamped lines of somebody else's prose under a title that
  already says what the offer is, on the one surface that is scanned twenty at a time — it cost about a third of a
  card's height for a sentence the detail column renders properly a few hundred pixels to the right.
  `shared/text/plain-text.ts` went with it: the card was its only consumer, and a tested util nothing calls is still
  dead code.
- **`lg-markdown` pushes every heading two levels down.** The advert's text is somebody else's and `#` renders an
  `<h1>`, so an ad that opened with its own title claimed the page's heading. Two levels and not one, because the text
  sits in a panel whose own heading is an
  `<h2>`.
- **The summary panel above the advert is the source's own `description`, not a summary this tool writes.** It appears
  only when the enriched `full_text` is on screen — without it the advert panel *is* that description, and the same
  paragraph twice says nothing the second time. The caption says where it came from, because the field is easy to
  mistake for something generated here.

