import { ChangeDetectionStrategy, Component, computed, effect, inject, input } from '@angular/core';
import { Router } from '@angular/router';
import { injectDispatch } from '@ngrx/signals/events';
import { TranslocoPipe } from '@jsverse/transloco';
import { shortlistEvents } from '@core/store/shortlist.events';
import { ShortlistStore } from '@core/store/shortlist.store';
import { ShortlistFilters } from '@core/model/shortlist-page';
import { SCORE_THRESHOLDS } from '@shared/shared.ports';
import { EmptyState } from '@shared/empty-state/empty-state';
import { LoadMore } from '@shared/load-more/load-more';
import { Icon } from '@shared/icon/icon';
import { PageHeader } from '@shared/page-header/page-header';
import { OfferCard } from './offer-card/offer-card';

type BandFilter = 'all' | 'shortlist' | 'review';

@Component({
  selector: 'lg-shortlist-page',
  imports: [EmptyState, Icon, LoadMore, OfferCard, PageHeader, TranslocoPipe],
  templateUrl: './shortlist-page.html',
  styleUrl: './shortlist-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShortlistPage {
  private readonly router = inject(Router);
  private readonly dispatch = injectDispatch(shortlistEvents);
  protected readonly store = inject(ShortlistStore);

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

  constructor() {
    effect(() => this.dispatch.opened(this.filters()));
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
