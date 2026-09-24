import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DOCUMENT,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  signal,
  viewChild,
} from '@angular/core';
import {takeUntilDestroyed, toSignal} from '@angular/core/rxjs-interop';
import {ActivatedRoute, NavigationEnd, Params, Router, RouterLink, RouterOutlet} from '@angular/router';
import {debounceTime, filter, map, Subject} from 'rxjs';
import {injectDispatch} from '@ngrx/signals/events';
import {TranslocoPipe} from '@jsverse/transloco';
import {shortlistEvents} from '@core/store/shortlist.events';
import {ConfigStore} from '@core/store/config.store';
import {ShortlistStore} from '@core/store/shortlist.store';
import {applicationEvents} from '@core/store/applications.events';
import {ApplicationsStore} from '@core/store/applications.store';
import {ApplicationStatus} from '@core/model/application';
import {ShortlistFilters} from '@core/model/shortlist-page';
import {SCORE_THRESHOLDS} from '@shared/shared.ports';
import {EmptyState} from '@shared/empty-state/empty-state';
import {LoadMore} from '@shared/load-more/load-more';
import {Icon} from '@shared/icon/icon';
import {PageHeader} from '@shared/page-header/page-header';
import {OfferCard} from './offer-card/offer-card';
import {FacetPanel} from './facet-panel/facet-panel';
import {SavedViews} from './saved-views/saved-views';
import {SortMenu} from './sort-menu/sort-menu';

type BandFilter = 'all' | 'shortlist' | 'review' | 'discarded';

/**
 * One filter the panel hides, as the bar displays it.
 *
 * <p>Data and not a sentence: `label` and `valueKey` are catalog keys and the template pipes
 * them, because the value sits in a different place in every language. `clear` is what
 * removing this chip writes to the query string — by name, so a chip removes its own filter
 * and nothing else.
 */
interface FacetChip {
  readonly id: string;
  readonly label: string;
  readonly valueKey: string | null;
  readonly params: Record<string, string | number>;
  readonly clear: Params;
}

@Component({
    selector: 'lg-shortlist-page',
    imports: [
        EmptyState,
        Icon,
        LoadMore,
      FacetPanel,
        OfferCard,
      PageHeader,
      SavedViews,
      SortMenu,
        RouterLink,
        RouterOutlet,
        TranslocoPipe,
    ],
    templateUrl: './shortlist-page.html',
    styleUrl: './shortlist-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
    // Whether the right column is showing an offer. On the host rather than on a wrapper,
    // because below the breakpoint the detail replaces the whole left column rather than
    // sitting beside it — one flag, read by every rule that needs it.
    host: {'[class.detail-open]': 'selectedId() !== null'},
})
export class ShortlistPage {
    private readonly router = inject(Router);
    private readonly route = inject(ActivatedRoute);
    private readonly dispatch = injectDispatch(shortlistEvents);
    protected readonly store = inject(ShortlistStore);
    private readonly applicationDispatch = injectDispatch(applicationEvents);
    private readonly applications = inject(ApplicationsStore);

    /** Where each offer's application stands, so every card shows it, picked or not. */
    protected readonly statusByOffer = computed(
        () =>
            new Map<number, ApplicationStatus>(
                this.applications.applications().map((application) => [application.offerId, application.status]),
            ),
    );

    private readonly listPane = viewChild<ElementRef<HTMLElement>>('listPane');

  private readonly confirmArchive = viewChild<ElementRef<HTMLDialogElement>>('confirmArchive');
  private readonly confirmOne = viewChild<ElementRef<HTMLDialogElement>>('confirmOne');

  /** The single-offer write `a` is waiting to have confirmed, because the offer has a package. */
  protected readonly pendingOne = signal<{ readonly id: number; readonly archived: boolean } | null>(null);

  /**
   * The offer `a` just took off this side of the list, and the one to open once it is gone.
   * Kept until the write settles: on success the neighbour opens, on failure the reader stays
   * on the offer and reads the error where it has always been shown.
   */
  private readonly afterArchive = signal<{ readonly id: number; readonly next: number | null } | null>(null);
  private readonly document = inject(DOCUMENT);
    private readonly injector = inject(Injector);

    /**
     * The offer a key press asked for, cleared once the focus has followed it there.
     *
     * A flag rather than "focus whatever is selected": a deep link and a mouse click both
     * change the selection too, and pulling the focus into the list on either of those would
     * move it away from what the reader was doing.
     */
    private readonly focusWanted = signal<number | null>(null);

    /**
     * The URL, only as a reason to look again — the same shape the shell uses, and for the
     * same reason: route state is read from the snapshot, so something has to say when the
     * snapshot changed, and RxJS stays at the I/O boundary, which the router is.
     */
    private readonly navigated = toSignal(
        this.router.events.pipe(
            filter((event) => event instanceof NavigationEnd),
            map(() => this.router.url),
        ),
        {initialValue: this.router.url},
    );

    /**
     * Which offer the child route is showing. Read from the route rather than held here: the
     * URL is what a deep link, the back button and a card click all agree on, and a second
     * copy in a signal disagrees with it the first time one of the three is used.
     */
    protected readonly selectedId = computed<number | null>(() => {
        this.navigated();
        const raw = this.route.snapshot.firstChild?.paramMap.get('id') ?? null;
        const id = Number(raw);
        return raw !== null && Number.isFinite(id) ? id : null;
    });

    /**
     * Filters live in the query string, not in the component: a shortlist worth
     * discussing is a link someone can send. `withComponentInputBinding()` in
     * app.config.ts is what binds these.
     *
     * Every one needs the transform. Router input binding writes `undefined` for a
     * parameter that is absent from the URL rather than leaving the declared
     * default in place, and the first `q().trim()` on that undefined throws inside
     * the template — which leaves the page half-rendered with no console error
     * pointing anywhere near the cause.
     */
    readonly q = input('', {transform: (value: string | undefined) => value ?? ''});
    readonly band = input<BandFilter, BandFilter | undefined>('all', {
        transform: (value) => value ?? 'all',
    });
  /**
   * Every portal the list is narrowed to, empty for all of them.
   *
   * <p>Singular, and holding a list: the name is the query parameter's, and router input
   * binding matches on it. Renaming it to `portals` would need an alias, which the lint
   * rule refuses and which would be the wrong trade anyway — the wire name is what every
   * existing link carries.
   *
   * <p>The parameter keeps its singular name and repeats — `?portal=a&portal=b` — so every
   * link written while it took one still means what it meant, on this side and on the
   * server's. Router input binding hands over a string for one value and an array for
   * several, which is what the transform is flattening; the same `undefined` trap as every
   * other input is why it has one at all.
   */
  readonly portal = input<readonly string[], string | readonly string[] | undefined>([], {
    transform: (value) => (value === undefined ? [] : typeof value === 'string' ? [value] : value),
  });

  /**
   * The other two spellings of the score axis. Null and not zero, because zero is a score
   * an offer can actually have — a `0` dropped as "the default" would be a filter that
   * cannot be expressed. The server refuses a band and a range together; nothing here ever
   * produces the pair, which is what the writers below are for.
   */
  readonly minScore = input<number | null, string | undefined>(null, {
    transform: (value) => ShortlistPage.score(value),
  });
  readonly maxScore = input<number | null, string | undefined>(null, {
    transform: (value) => ShortlistPage.score(value),
  });
  readonly scoreState = input('any', {transform: (value: string | undefined) => value ?? 'any'});
    /**
     * Which side of the archive is on screen. A query parameter like the rest, so a link to
     * the archive is a link; and a string in the URL rather than a boolean, because that is
     * what a query string carries.
     */
    readonly archived = input(false, {transform: (value: string | undefined) => value === '1'});

  /**
   * Which order, and therefore which cursor. The server composes the ORDER BY and the
   * keyset comparison from one expression and refuses a cursor minted under another sort,
   * so this has to travel with the filters rather than beside them — `opened` already
   * clears `entries` and `cursor` on any change here, which is exactly what a sort change
   * needs and what stops a mismatched cursor ever being sent.
   */
  readonly sort = input('score', {transform: (value: string | undefined) => value ?? 'score'});

  /**
   * When the engagement starts. Four values that partition the set, `unknown` included:
   * most adverts name no resolvable day, so a window without it would hide most of the
   * list while looking exactly like a filter that worked.
   */
  readonly startWindow = input('any', {transform: (value: string | undefined) => value ?? 'any'});

  /**
   * The committed minimum in months, as the query string carries it. Zero is no minimum,
   * and so is anything that is not a number: a hand-edited link must not be able to empty
   * the list for a reason nobody can see.
   */
  readonly minMonths = input(0, {
    transform: (value: string | undefined) => {
      const months = Number(value ?? 0);
      return Number.isFinite(months) && months > 0 ? months : 0;
    },
  });

  /** Only offers whose deadline has not passed, plus every offer that stated none. */
  readonly deadlineOpen = input(false, {transform: (value: string | undefined) => value === '1'});

  /** Only offers the similarity pass marked as possibly the same project as an older one. */
  readonly possibleDuplicates = input(false, {transform: (value: string | undefined) => value === '1'});

  /** A profile topic: offers the scorer found naming it, in every band. */
  readonly topic = input('', {transform: (value: string | undefined) => value ?? ''});

  private readonly config = inject(ConfigStore);

  /**
   * The configured topics, interest first, straight from the rules the app loads at startup.
   * The names are the profile's own, so a filter picked here is exactly what the scorer stored.
   */
  protected readonly topicOptions = computed<readonly string[]>(() => {
    const rules = this.config.rules();
    return rules === null
      ? []
      : [...rules.interestTopics, ...rules.disinterestTopics].map((topic) => topic.name);
  });

  /**
   * Words to find offers near.
   *
   * <p><b>This narrows and never reorders.</b> The list stays in whichever of the six orders
   * is selected, so the first row is not the best match — there is no such thing here, and an
   * interface that implies one would have people reading row one as the answer.
   *
   * <p>Not routed through {@link setFilter}: `DEFAULTS` holds `'any'`, and somebody searching
   * for the word *any* would have their filter silently dropped. The same reason the score
   * bounds bypass it.
   */
  readonly semantic = input('', {transform: (value: string | undefined) => value ?? ''});

  /**
   * An offer to find offers near. Costs no model call — both vectors are already stored — and
   * the anchor is in its own result, because its distance to itself is zero.
   */
  readonly similar = input<number | null, string | undefined>(null, {
    transform: (value) => {
      const id = Number(value);
      return value === undefined || value === '' || !Number.isFinite(id) ? null : id;
    },
  });

  /**
   * The six sort keys the server offers, in the order they are worth trying. The names are
   * the server's; a union type here would disagree with it the first time one is added, the
   * same reason nothing in this browser names a weight or a filter stage.
   *
   * <p>`duration-asc` is the one reverse the server has, and the reason there is no direction
   * toggle beside this list: over there a direction is not a modifier but part of the keyset
   * tuple, along with the sentinel that keeps "not stated" at the end. A toggle would work on
   * one of six orders, which is a control that lies at rest.
   */
  protected readonly sortOptions = [
    'score',
    'fresh',
    'start',
    'deadline',
    'duration',
    'duration-asc',
  ] as const;

  protected readonly startWindowOptions = ['any', 'now', 'soon', 'later', 'unknown'] as const;

  /** Three steps and off. Finer than this is a number nobody has an opinion about. */
  protected readonly minMonthsOptions = [0, 3, 6, 12] as const;

    /**
     * The same two numbers the rings band on and the rules screen prints, from one source.
     * They were literals here, and they decided which offers the band buttons showed — so a
     * threshold changed in the file would have moved the rules screen and the histogram while
     * this screen quietly kept filtering on the old one. The upper bound of the middle band is
     * derived rather than written, for the same reason.
     */
    private readonly thresholds = inject(SCORE_THRESHOLDS);

    protected readonly shortlistAt = computed(() => this.thresholds().shortlistAt);
    protected readonly reviewAt = computed(() => this.thresholds().reviewAt);

    protected readonly bandOptions = computed<
        readonly { id: BandFilter; label: string; params: Record<string, number> }[]
    >(() => [
        {id: 'all' as BandFilter, label: 'shortlist.bandAll', params: {} as Record<string, number>},
        {id: 'shortlist', label: 'shortlist.bandAbove', params: {score: this.shortlistAt()}},
        {
            id: 'review',
            label: 'shortlist.bandBetween',
            params: {from: this.reviewAt(), to: this.shortlistAt() - 1},
        },
        {id: 'discarded', label: 'shortlist.bandBetween', params: {from: 0, to: this.reviewAt() - 1}},
    ]);

    /**
     * The filters, as the query string states them. An effect rather than `ngOnInit`, because
     * they change while the screen is open and every change is a new first page.
     */
    private readonly filters = computed<ShortlistFilters>(() => ({
        q: this.q(),
        band: this.band(),
      minScore: this.minScore(),
      maxScore: this.maxScore(),
      scoreState: this.scoreState(),
      portals: this.portal(),
        archived: this.archived(),
      sort: this.sort(),
      startWindow: this.startWindow(),
      minMonths: this.minMonths(),
      deadlineOpen: this.deadlineOpen(),
      possibleDuplicates: this.possibleDuplicates(),
      topic: this.topic(),
      semantic: this.semantic(),
      similarTo: this.similar(),
    }));

    /**
     * Whether both columns are on screen, which is the whole condition for opening one by
     * itself.
     *
     * <p>The number is the stylesheet's, stated once on each side and tied together by this
     * comment: below it the detail <em>replaces</em> the list rather than sitting beside it,
     * so auto-selecting there would answer "show me the shortlist" with a single offer and
     * the reader would have to press back to reach the list they asked for.
     *
     * <p>`matchMedia` and not a measured width: it is a media query and not a rendering-
     * lifecycle API, so it answers correctly in a backgrounded tab, unlike a
     * `ResizeObserver`. Guarded because jsdom has neither it nor `addEventListener` on the
     * result, and a specs run must not depend on either.
     */
    private static readonly BOTH_COLUMNS = '(width >= 72rem)';

  /** Read by the template as well: the sentinel's root depends on it. */
  protected readonly bothColumns = signal(false);

    constructor() {
      const view = this.document.defaultView;
        if (typeof view?.matchMedia === 'function') {
            const query = view.matchMedia(ShortlistPage.BOTH_COLUMNS);
            this.bothColumns.set(query.matches);
            query.addEventListener?.('change', (event) => this.bothColumns.set(event.matches));
        }

        effect(() => this.dispatch.opened(this.filters()));
        // The board is the source of every status; read it once for the cards.
        this.applicationDispatch.opened();

      this.typed
        .pipe(debounceTime(250), takeUntilDestroyed())
        .subscribe((value) => this.write({q: value.trim() === '' ? null : value}, true));

        /*
         * An empty right column beside a full list is a page waiting for a click it does not
         * need: the first entry is the highest-scoring one the current filters produced, which
         * is the offer somebody opening this screen was going to open anyway.
         *
         * `replaceUrl`, because `/shortlist` and `/shortlist/:id` are the same screen once both
         * columns fit — a history entry between them would make the back button undo a
         * selection nobody made. Only when nothing is selected, so a filter change never moves
         * the reader off the offer they are reading.
         */
        effect(() => {
            const first = this.visible()[0];
            if (this.selectedId() !== null || first === undefined || !this.bothColumns()) {
                return;
            }
            void this.router.navigate(['/shortlist', first.offer.id], {
                queryParamsHandling: 'preserve',
                replaceUrl: true,
            });
        });

        // After `a`: the write settled when `archiving` is clear again. Gone from the list means it
        // worked, and the neighbour opens the way j/k would open it; still listed means it failed.
        effect(() => {
            const pending = this.afterArchive();
            if (pending === null || this.store.archiving() !== null) {
                return;
            }
            this.afterArchive.set(null);
            const stillListed = this.visible().some((entry) => entry.offer.id === pending.id);
            if (stillListed || pending.next === null) {
                return;
            }
            this.focusWanted.set(pending.next);
            void this.router.navigate(['/shortlist', pending.next], {queryParamsHandling: 'preserve'});
        });

        // The focus follows a key press, which is what makes the browser scroll the card into
        // view — no measuring, no `scrollIntoView`, and the ring lands where the reader is.
        // `afterNextRender` because `aria-current` is on the new card only after the change
        // detection the navigation triggers.
        effect(() => {
            const wanted = this.focusWanted();
            if (wanted === null || this.selectedId() !== wanted) {
                return;
            }
            this.focusWanted.set(null);
            afterNextRender(
                () => {
                    this.listPane()
                        ?.nativeElement.querySelector<HTMLAnchorElement>('[aria-current="true"]')
                        ?.focus();
                },
                {injector: this.injector},
            );
        });

        // A new offer starts at its own top. The right column is one element that survives the
      // navigation, so without this the second offer opens wherever the first one was left —
      // and since that column stopped having a scroller of its own, what has to be reset is
      // the document. The list column is pinned, so the reader keeps it either way.
        // `scrollTop` rather than `scrollTo`: jsdom implements the property and not the method,
        // and jumping is what is wanted here anyway — a smooth scroll through a whole advert
      // between two clicks reads as lag. The guard is truthy rather than `!== null` because
      // jsdom has no `scrollingElement` at all: typed as `Element | null`, it arrives
      // `undefined`, walks straight through a null check and takes all ten specs of this
      // screen down with "Cannot set properties of undefined".
        effect(() => {
            this.selectedId();
          const scroller = this.document.scrollingElement;
          if (scroller) {
            scroller.scrollTop = 0;
            }
        });
    }

    /** What the server sent for these filters. The browser no longer decides what is shown. */
    protected readonly visible = computed(() => this.store.entries());

  /**
   * The query string as it stands, which is the whole of what a saved view holds.
   *
   * <p>Read off the router's own URL rather than assembled from the eight inputs: what is
   * saved has to be exactly what would be restored, and an assembled copy disagrees with
   * the URL the first time a filter is added. `navigated()` is the reason to look again,
   * the same shape `selectedId` uses.
   */
  protected readonly currentQuery = computed(() => {
    this.navigated();
    return this.router.url.split('?')[1] ?? '';
  });

  /**
   * A saved view, applied.
   *
   * <p>It replaces the query string rather than merging into it — no `queryParamsHandling`
   * — because a view is the screen as it was saved, and merged it would be that view plus
   * whatever the reader happened to have set. The selected offer is a path segment and
   * survives, which is right: the list changes underneath it, as on any filter change.
   */
  protected applyView(query: string): void {
    const params: Params = {};
    new URLSearchParams(query).forEach((value, key) => {
      const seen = params[key] as string | string[] | undefined;
      // A repeated parameter is a list — `portal` is one — and `URLSearchParams` hands
      // the pairs over one at a time, so the second `portal=` would otherwise replace
      // the first and a two-portal view would restore as a one-portal view.
      params[key] =
        seen === undefined ? value : Array.isArray(seen) ? [...seen, value] : [seen, value];
    });
    void this.router.navigate([], {queryParams: params});
  }

    protected readonly filtered = computed(
      () =>
        this.q() !== '' ||
        this.band() !== 'all' ||
        this.archived() ||
        this.sort() !== 'score' ||
        this.related() !== null ||
        this.facetChips().length > 0,
    );

  /**
   * What the panel is hiding, one chip per filter.
   *
   * <p>Chips for exactly what is behind the trigger and nothing else: the search text is in
   * its own field and the band is lit in its own group, three centimetres above, and a chip
   * for a control that is already showing its state is a second copy of it. That is also
   * what keeps the badge honest — the count on the trigger is this list's length, so the
   * two can never disagree.
   *
   * <p>A portal is one chip each rather than one chip saying "3 portals": the point of a
   * chip is that it can be removed on its own.
   */
  /**
   * The relatedness filter as a chip, or null.
   *
   * <p><b>Its own computed and deliberately not part of {@link facetChips}.</b> The panel's
   * badge counts that list, and its own rule is that the count is exactly what sits behind the
   * trigger. This filter does not, so folding it in would make the badge claim a filter the
   * panel does not contain — the same class of quiet disagreement the shortlist has already
   * paid for twice. It is rendered first in the same row with the same markup, so it reads as
   * one row of chips either way.
   *
   * <p>Removing it writes both parameters away, because the two are one filter with two
   * spellings and only one of them is ever set.
   */
  protected readonly relatedChip = computed<FacetChip | null>(() => {
    if (this.semantic() !== '') {
      return {
        id: 'related-text',
        label: 'shortlist.facet.relatedText',
        valueKey: null,
        params: {value: this.semantic()},
        clear: {semantic: null, similar: null},
      };
    }
    if (this.similar() === null) {
      return null;
    }
    // The query string carries the id and nothing else, so the title comes from the server on
    // the same response the list did. Before the first page arrives there is no title yet, and
    // the unnamed form is what the chip says for that moment — the list is loading anyway.
    const title = this.store.relatedTo();
    const chip: FacetChip = {
      id: 'related-offer',
      label: title === null ? 'shortlist.facet.relatedOfferUnnamed' : 'shortlist.facet.relatedOffer',
      valueKey: null,
      params: title === null ? {} : {value: title},
      clear: {semantic: null, similar: null},
    };
    return chip;
  });

  /**
   * Which of the four count sentences the header says.
   *
   * <p>Two axes and therefore four keys: which side of the archive is on screen, and whether
   * the match was bounded by a relatedness filter. The second half is the honest part — with
   * the filter on, the number beside the list describes a neighbourhood the search chose, not
   * what the market produced.
   */
  protected readonly countKey = computed(() => {
    if (this.relatedChip() === null) {
      return this.archived() ? 'shortlist.countArchived' : 'shortlist.count';
    }
    return this.archived() ? 'shortlist.countArchivedRelated' : 'shortlist.countRelated';
  });

  /** Whether a relatedness filter is on at all, for {@link filtered}. */
  protected readonly related = computed(() => this.relatedChip());

  /**
   * How much of the working list this filter can reach, while that is worth saying.
   *
   * <p>Null once the index has caught up, so the note removes itself with nothing to configure
   * and nothing to dismiss. Null too when the installation cannot search this way at all,
   * which is the same absence the control itself has.
   */
  protected readonly relatedCoverage = computed(() => {
    const coverage = this.store.relatedCoverage();
    if (coverage === null || this.relatedChip() === null || coverage.readable >= coverage.total) {
      return null;
    }
    return coverage;
  });

  /**
   * Whether this installation can answer a relatedness question.
   *
   * <p>Off, every trace of the feature is absent rather than disabled: a control the server
   * would refuse is worse than one that says it does not exist, which is the same rule the
   * sort list follows.
   */
  protected readonly relatedAvailable = computed(() => this.store.relatedCoverage() !== null);

  protected readonly facetChips = computed<readonly FacetChip[]>(() => {
    const chips: FacetChip[] = [];

    for (const name of this.portal()) {
      const rest = this.portal().filter((other) => other !== name);
      chips.push({
        id: `portal:${name}`,
        label: 'shortlist.facet.portal',
        valueKey: null,
        params: {value: name},
        clear: {portal: rest.length > 0 ? [...rest] : null},
      });
    }

    const min = this.minScore();
    const max = this.maxScore();
    if (min !== null || max !== null) {
      chips.push({
        id: 'score-range',
        label:
          min !== null && max !== null
            ? 'shortlist.facet.scoreBetween'
            : min !== null
              ? 'shortlist.facet.scoreFrom'
              : 'shortlist.facet.scoreTo',
        valueKey: null,
        params: {from: min ?? 0, to: max ?? 0},
        clear: {minScore: null, maxScore: null},
      });
    }

    if (this.scoreState() !== 'any') {
      chips.push({
        id: 'score-state',
        label: 'shortlist.facet.scoreState',
        valueKey: `shortlist.scoreStates.${this.scoreState()}`,
        params: {},
        clear: {scoreState: null},
      });
    }

    if (this.startWindow() !== 'any') {
      chips.push({
        id: 'start',
        label: 'shortlist.facet.start',
        valueKey: `shortlist.start.${this.startWindow()}`,
        params: {},
        clear: {startWindow: null},
      });
    }

    if (this.minMonths() > 0) {
      chips.push({
        id: 'duration',
        label: 'shortlist.facet.durationMonths',
        valueKey: null,
        params: {months: this.minMonths()},
        clear: {minMonths: null},
      });
    }

    if (this.deadlineOpen()) {
      chips.push({
        id: 'deadline',
        label: 'shortlist.facet.deadline',
        valueKey: null,
        params: {},
        clear: {deadlineOpen: null},
      });
    }

    if (this.possibleDuplicates()) {
      chips.push({
        id: 'possibleDuplicates',
        label: 'shortlist.facet.possibleDuplicates',
        valueKey: null,
        params: {},
        clear: {possibleDuplicates: null},
      });
    }

    if (this.topic() !== '') {
      chips.push({
        id: 'topic',
        label: 'shortlist.facet.topic',
        valueKey: null,
        params: {value: this.topic()},
        clear: {topic: null},
      });
    }

    return chips;
  });

    /** Asked for when the reader reaches the end of what is loaded. */
    protected loadMore(): void {
        if (this.store.hasMore() && !this.store.loadingMore()) {
            this.dispatch.moreRequested();
        }
    }

    /**
     * The archive is a separate parameter and not a fourth band, because it composes with
     * the bands and with the search: "archived, above 70" is a question worth asking, and a
     * fourth button inside a group of mutually exclusive ones reads as exclusive.
     */
    protected toggleArchive(): void {
        void this.router.navigate([], {
            queryParams: {archived: this.archived() ? null : '1'},
            queryParamsHandling: 'merge',
        });
    }

  /**
   * A card's checkbox, with the Shift state the click carried.
   *
   * The range is resolved against `entries` — the loaded list in the order the server sent
   * it, which is the same list the arrow keys walk — and never against the DOM. Both ends
   * are inclusive. An anchor that is no longer in the list, because its row was archived,
   * falls back to a plain toggle rather than guessing at what was meant.
   *
   * A range can therefore span a page boundary, which is most of what makes the gesture
   * worth having. That works only because the selection survives paging *and* the anchor is
   * an id: as an index it would point at a different offer after every load-more, silently.
   */
  protected onPick(id: number, range: boolean): void {
    const entries = this.visible();
    const anchor = this.store.pickAnchor();
    if (range && anchor !== null) {
      const from = entries.findIndex((entry) => entry.offer.id === anchor);
      const to = entries.findIndex((entry) => entry.offer.id === id);
      if (from !== -1 && to !== -1) {
        const [lo, hi] = from <= to ? [from, to] : [to, from];
        this.dispatch.rangePicked(entries.slice(lo, hi + 1).map((entry) => entry.offer.id));
        return;
      }
    }
    this.dispatch.offerPicked({id, picked: !this.store.pickedIds().has(id)});
  }

  protected clearPicks(): void {
    this.dispatch.picksCleared();
  }

  /**
   * Archiving is reversible, but it is still a write over a set somebody assembled by hand,
   * so the count is read back before it goes out.
   *
   * Optional-called throughout: jsdom implements `HTMLDialogElement` as a bare `HTMLElement`
   * carrying an `open` attribute and nothing else — no `showModal`, no `close` — exactly like
   * `Element.scrollTo` and `document.scrollingElement`. An unguarded call takes down every
   * spec of this screen from inside a handler, far from anything that names a dialog.
   */
  protected askToArchivePicked(): void {
    this.confirmArchive()?.nativeElement.showModal?.();
  }

  protected confirmArchivePicked(): void {
    this.dispatch.bulkArchiveRequested(this.store.picked());
    this.confirmArchive()?.nativeElement.close?.();
    // The bar is destroyed the moment the selection empties, so focus would fall to the
    // body and the keyboard path would be lost mid-triage — the one thing the split view
    // was rebuilt to protect. The pane carries `tabindex="0"` precisely so it can hold it.
    this.listPane()?.nativeElement.focus?.();
  }

  /**
   * `a`: the open offer leaves the side being read — archived from the working list, restored
   * from the archive, by the same rule as the detail's button.
   *
   * <p><b>Direct unless the offer has a package.</b> Archiving deletes a package nobody sent, and a
   * restore resets the application to NEW without rebuilding it, so one stray key would cost a
   * document. The browser cannot see whether it was ever sent; a package on disk stands in for
   * that, which asks once too often rather than once too rarely.
   */
  private archiveOpenOffer(): void {
    const entry = this.store.selected();
    const id = this.selectedId();
    if (entry === null || id === null || entry.offer.id !== id || this.store.archiving() !== null) {
      return;
    }
    const archived = entry.offer.archivedAt === null;
    if (entry.offer.packageDir !== null) {
      this.pendingOne.set({id, archived});
      this.confirmOne()?.nativeElement.showModal?.();
      return;
    }
    this.archiveOne(id, archived);
  }

  /**
   * The neighbour is decided before the row goes: below, or above when it was the last. Like a
   * mail client, triage continues where the reader already is.
   */
  private archiveOne(id: number, archived: boolean): void {
    const entries = this.visible();
    const index = entries.findIndex((entry) => entry.offer.id === id);
    const next = index === -1 ? null : (entries[index + 1] ?? entries[index - 1])?.offer.id ?? null;
    this.dispatch.archiveRequested({id, archived});
    this.afterArchive.set({id, next});
  }

  protected confirmOneArchive(): void {
    const pending = this.pendingOne();
    this.confirmOne()?.nativeElement.close?.();
    this.pendingOne.set(null);
    if (pending !== null) {
      this.archiveOne(pending.id, pending.archived);
    }
    this.listPane()?.nativeElement.focus?.();
  }

  protected cancelOneArchive(): void {
    this.confirmOne()?.nativeElement.close?.();
    this.pendingOne.set(null);
    this.listPane()?.nativeElement.focus?.();
  }

  /** A cancelled confirmation is not a cleared selection. */
  protected cancelArchivePicked(): void {
    this.confirmArchive()?.nativeElement.close?.();
  }

  /**
   * A bound as the query string carries it. Null for absent, for anything that is not a
   * number, and never for `0`: a hand-edited link must not be able to empty the list for a
   * reason nobody can see, and zero is a score an offer can have.
   */
  private static score(value: string | undefined): number | null {
    if (value === undefined || value.trim() === '') {
      return null;
    }
    const score = Number(value);
    return Number.isFinite(score) ? score : null;
  }

  /**
   * `''`, `'all'`, `'score'`, `'any'` and `'0'` are the defaults, and a default is dropped
   * from the URL rather than written into it: a link should say what is unusual about the
   * view and nothing else.
   *
   * <p>Only the stringy filters go through it. A score bound must not: `'0'` is in this set,
   * and `minScore=0` is a filter somebody asked for.
   */
  private static readonly DEFAULTS = new Set(['', 'all', 'score', 'any', '0']);

  /**
   * The one writer of the query string.
   *
   * <p>`merge`, so a write names only what it changes and every other filter, the sort and
   * the archive side all stay where they were. A null drops the parameter.
   *
   * <p>`replaceUrl` for the search alone: a debounced keystroke is still one navigation, and
   * pushed it makes the back button walk back through a word letter by letter instead of
   * leaving the screen.
   */
  private write(queryParams: Params, replaceUrl = false): void {
    void this.router.navigate([], {queryParams, queryParamsHandling: 'merge', replaceUrl});
  }

  protected setFilter(
    key: 'q' | 'band' | 'sort' | 'startWindow' | 'minMonths',
    value: string,
  ): void {
    this.write({[key]: ShortlistPage.DEFAULTS.has(value) ? null : value});
  }

  /** The panel emits a number; the query string carries a string. Zero is no minimum. */
  protected setMinMonths(months: number): void {
    this.setFilter('minMonths', String(months));
  }

  /**
   * The three spellings of the score axis, and each writer clears the other two.
   *
   * <p>The server refuses two at once with a sentence naming both, because intersected they
   * return rows and a short list after filtering is indistinguishable from a quiet market.
   * This is the half that makes sure nobody ever sees that sentence: the band group, the
   * state group and the range are one control group with three modes, so choosing one mode
   * is choosing against the others.
   */
  protected setBand(band: BandFilter): void {
    this.write({
      band: band === 'all' ? null : band,
      minScore: null,
      maxScore: null,
      scoreState: null,
    });
  }

  protected setScoreState(state: string): void {
    this.write({
      scoreState: state === 'any' ? null : state,
      band: null,
      minScore: null,
      maxScore: null,
    });
  }

  protected setScoreRange(range: { readonly min: number | null; readonly max: number | null }): void {
    this.write({
      minScore: range.min,
      maxScore: range.max,
      band: null,
      scoreState: null,
    });
  }

  /**
   * Find offers near these words.
   *
   * <p>Clears `q` and `similar`, because the three are one question asked three ways and the
   * server refuses two of them together. It writes the typed words out of the field and into
   * the chip row, which is what makes the filter visible as a filter.
   *
   * <p>Not through {@link setFilter}: `DEFAULTS` contains `'any'`, and a search for that word
   * would be dropped without a trace.
   */
  protected findRelated(text: string): void {
    const phrase = text.trim();
    if (phrase === '') {
      return;
    }
    this.write({semantic: phrase, q: null, similar: null});
  }

  /**
   * One portal in or out, the rest untouched. An empty selection drops the parameter rather
   * than writing an empty one, which is what keeps "every portal" the absence of a filter.
   */
  protected togglePortal(name: string): void {
    const picked = this.portal();
    const next = picked.includes(name)
      ? picked.filter((other) => other !== name)
      : [...picked, name];
    this.write({portal: next.length > 0 ? next : null});
  }

  /** What one chip removes: its own parameters, by name, and nothing else. */
  protected removeFacet(chip: FacetChip): void {
    this.write(chip.clear);
  }

  /**
   * Read the list again, now that the reader has said so. The same event a filter change
   * raises, so there is one path that fetches a page and the hint knows nothing about it.
   */
  protected reload(): void {
    this.dispatch.opened(this.filters());
  }

  protected toggleDeadlineOpen(): void {
        void this.router.navigate([], {
          queryParams: {deadlineOpen: this.deadlineOpen() ? null : '1'},
            queryParamsHandling: 'merge',
        });
    }

  protected setTopic(name: string): void {
    void this.router.navigate([], {
      queryParams: {topic: name === '' ? null : name},
      queryParamsHandling: 'merge',
    });
  }

  protected togglePossibleDuplicates(): void {
    void this.router.navigate([], {
      queryParams: {possibleDuplicates: this.possibleDuplicates() ? null : '1'},
      queryParamsHandling: 'merge',
    });
  }

    /**
     * Arrow keys and `j`/`k` walk the list, because triage is twenty offers in a row and a
     * mouse round trip per offer is the thing this screen was rebuilt to remove.
     *
     * <p>Bound on the pane rather than on the document: `keydown` bubbles from the focused
     * card link, so the handler only fires while the focus is in the list. That is what lets
     * `j` stay a letter in the search field, which sits outside the pane, and leaves the
     * arrow keys scrolling the advert while the reader is in the detail column — no target
     * sniffing anywhere.
     *
     * <p>Past the last loaded entry it asks for the next page and stays put, rather than
     * doing nothing: the list is keyset-paged, so "next" beyond what is loaded does not exist
     * yet. After an archive the selected row is gone from `entries` and the index is -1; the
     * key then behaves as it does with nothing selected. Better a known first entry than a
     * guessed neighbour.
     */
    protected onListKey(event: KeyboardEvent): void {
        if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) {
            return;
        }
        if (event.key === 'a') {
            event.preventDefault();
            this.archiveOpenOffer();
            return;
        }
        const step =
            event.key === 'ArrowDown' || event.key === 'j'
                ? 1
                : event.key === 'ArrowUp' || event.key === 'k'
                    ? -1
                    : 0;
        const entries = this.visible();
        if (step === 0 || entries.length === 0) {
            return;
        }

        event.preventDefault();
        const current = entries.findIndex((entry) => entry.offer.id === this.selectedId());
        const next = current === -1 ? 0 : current + step;
        if (next < 0) {
            return;
        }
        if (next >= entries.length) {
            this.loadMore();
            return;
        }

        const id = entries[next].offer.id;
        this.focusWanted.set(id);
        void this.router.navigate(['/shortlist', id], {queryParamsHandling: 'preserve'});
    }

  /**
   * Typing, debounced.
   *
   * <p>Every keystroke used to be a navigation, a request and a history entry: "kubernetes"
   * was ten of each, nine of them thrown away by the `switchMap` behind them, and the back
   * button then walked back through the word one letter at a time. It is also what makes a
   * live region on the result count possible at all — announced per keystroke it would
   * chatter over the typing it is reporting on.
   *
   * <p>RxJS because this is the I/O boundary, which is the only place this repository uses
   * it; `takeUntilDestroyed` because the subject outlives nothing else.
   */
  private readonly typed = new Subject<string>();

    protected onInput(event: Event): void {
      this.typed.next((event.target as HTMLInputElement).value);
    }

  protected onSelect(
    key: 'sort' | 'startWindow' | 'minMonths',
    event: Event,
  ): void {
    this.setFilter(key, (event.target as HTMLSelectElement).value);
  }

  /**
   * The search only, which is the whole point of it sitting in the field.
   *
   * <p>It used to be `navigate([], {queryParams: {}})` — the whole query string, including
   * `archived`, so the ✕ beside the search took a reader out of the archive and back onto
   * the working list. Everything else is cleared from the chip row, where what is being
   * cleared can be seen.
   */
  protected clearSearch(): void {
    this.write({q: null});
  }

  /**
   * Every filter off, by name.
   *
   * <p>The sort stays: an order is not a filter, and a reader who chose one did not ask for
   * it to be forgotten. So does `archived`: a set is not a filter either, and dropping it
   * would answer "clear the filters" by leaving the archive.
   */
  protected clearAll(): void {
    this.write({
      q: null,
      band: null,
      portal: null,
      minScore: null,
      maxScore: null,
      scoreState: null,
      startWindow: null,
      minMonths: null,
      deadlineOpen: null,
      possibleDuplicates: null,
      topic: null,
      semantic: null,
      similar: null,
    });
    }
}
