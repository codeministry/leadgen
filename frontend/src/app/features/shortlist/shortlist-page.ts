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
import {toSignal} from '@angular/core/rxjs-interop';
import {ActivatedRoute, NavigationEnd, Router, RouterLink, RouterOutlet} from '@angular/router';
import {filter, map} from 'rxjs';
import {injectDispatch} from '@ngrx/signals/events';
import {TranslocoPipe} from '@jsverse/transloco';
import {shortlistEvents} from '@core/store/shortlist.events';
import {ShortlistStore} from '@core/store/shortlist.store';
import {ShortlistFilters} from '@core/model/shortlist-page';
import {SCORE_THRESHOLDS} from '@shared/shared.ports';
import {EmptyState} from '@shared/empty-state/empty-state';
import {LoadMore} from '@shared/load-more/load-more';
import {Icon} from '@shared/icon/icon';
import {PageHeader} from '@shared/page-header/page-header';
import {OfferCard} from './offer-card/offer-card';

type BandFilter = 'all' | 'shortlist' | 'review';

@Component({
  selector: 'lg-shortlist-page',
    imports: [
        EmptyState,
        Icon,
        LoadMore,
        OfferCard,
        PageHeader,
        RouterLink,
        RouterOutlet,
        TranslocoPipe,
    ],
  templateUrl: './shortlist-page.html',
  styleUrl: './shortlist-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
    // Whether the right column is showing an offer. On the host rather than on a wrapper,
    // because the page header sits outside the split and has to disappear with the list on a
    // narrow screen — one flag, read by every rule that needs it.
    host: {'[class.detail-open]': 'selectedId() !== null'},
})
export class ShortlistPage {
  private readonly router = inject(Router);
    private readonly route = inject(ActivatedRoute);
  private readonly dispatch = injectDispatch(shortlistEvents);
  protected readonly store = inject(ShortlistStore);

    private readonly detailPane = viewChild<ElementRef<HTMLElement>>('detailPane');
    private readonly listPane = viewChild<ElementRef<HTMLElement>>('listPane');
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
  readonly q = input('', { transform: (value: string | undefined) => value ?? '' });
  readonly band = input<BandFilter, BandFilter | undefined>('all', {
    transform: (value) => value ?? 'all',
  });
  readonly portal = input('', { transform: (value: string | undefined) => value ?? '' });
  /**
   * Which side of the archive is on screen. A query parameter like the rest, so a link to
   * the archive is a link; and a string in the URL rather than a boolean, because that is
   * what a query string carries.
   */
  readonly archived = input(false, { transform: (value: string | undefined) => value === '1' });

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
    { id: 'all' as BandFilter, label: 'shortlist.bandAll', params: {} as Record<string, number> },
    { id: 'shortlist', label: 'shortlist.bandAbove', params: { score: this.shortlistAt() } },
    {
      id: 'review',
      label: 'shortlist.bandBetween',
      params: { from: this.reviewAt(), to: this.shortlistAt() - 1 },
    },
  ]);

  /**
   * The filters, as the query string states them. An effect rather than `ngOnInit`, because
   * they change while the screen is open and every change is a new first page.
   */
  private readonly filters = computed<ShortlistFilters>(() => ({
    q: this.q(),
    band: this.band(),
    portal: this.portal(),
    archived: this.archived(),
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
    private static readonly BOTH_COLUMNS = '(width >= 64rem)';

    private readonly bothColumns = signal(false);

  constructor() {
      const view = inject(DOCUMENT).defaultView;
      if (typeof view?.matchMedia === 'function') {
          const query = view.matchMedia(ShortlistPage.BOTH_COLUMNS);
          this.bothColumns.set(query.matches);
          query.addEventListener?.('change', (event) => this.bothColumns.set(event.matches));
      }

    effect(() => this.dispatch.opened(this.filters()));

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
      // navigation, so without this the second offer opens wherever the first one was left.
      // `scrollTop` rather than `scrollTo`: jsdom implements the property and not the method,
      // and jumping is what is wanted here anyway — a smooth scroll through a whole advert
      // between two clicks reads as lag.
      effect(() => {
          this.selectedId();
          const pane = this.detailPane()?.nativeElement;
          if (pane !== undefined) {
              pane.scrollTop = 0;
          }
      });
  }

  /** What the server sent for these filters. The browser no longer decides what is shown. */
  protected readonly visible = computed(() => this.store.entries());

  protected readonly filtered = computed(
    () => this.q() !== '' || this.band() !== 'all' || this.portal() !== '' || this.archived(),
  );

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
      queryParams: { archived: this.archived() ? null : '1' },
      queryParamsHandling: 'merge',
    });
  }

  protected setFilter(key: 'q' | 'band' | 'portal', value: string): void {
    void this.router.navigate([], {
      queryParams: { [key]: value === '' || value === 'all' ? null : value },
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

  protected onInput(event: Event): void {
    this.setFilter('q', (event.target as HTMLInputElement).value);
  }

  protected onPortal(event: Event): void {
    this.setFilter('portal', (event.target as HTMLSelectElement).value);
  }

  protected clear(): void {
    void this.router.navigate([], { queryParams: {} });
  }
}
