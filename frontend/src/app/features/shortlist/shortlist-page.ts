import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input } from '@angular/core';
import { Router } from '@angular/router';
import { injectDispatch } from '@ngrx/signals/events';
import { shortlistEvents } from '@core/store/shortlist.events';
import { ShortlistStore } from '@core/store/shortlist.store';
import { EmptyState } from '@shared/empty-state/empty-state';
import { Icon } from '@shared/icon/icon';
import { PageHeader } from '@shared/page-header/page-header';
import { OfferCard } from './offer-card/offer-card';

type BandFilter = 'all' | 'shortlist' | 'review';

@Component({
  selector: 'lg-shortlist-page',
  imports: [EmptyState, Icon, OfferCard, PageHeader],
  templateUrl: './shortlist-page.html',
  styleUrl: './shortlist-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShortlistPage implements OnInit {
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

  protected readonly bandOptions: readonly { id: BandFilter; label: string }[] = [
    { id: 'all', label: 'All' },
    { id: 'shortlist', label: 'Above 70' },
    { id: 'review', label: '50 to 69' },
  ];

  protected readonly shortlistAt = 70;
  protected readonly reviewAt = 50;

  protected readonly visible = computed(() => {
    const needle = this.q().trim().toLowerCase();
    const band = this.band();
    const portal = this.portal();

    return this.store.entries().filter((entry) => {
      const value = entry.score.value;
      if (band === 'shortlist' && (value === null || value < this.shortlistAt)) {
        return false;
      }
      if (band === 'review' && (value === null || value >= this.shortlistAt || value < this.reviewAt)) {
        return false;
      }
      if (portal !== '' && !entry.sources.some((source) => source.portal === portal)) {
        return false;
      }
      if (needle === '') {
        return true;
      }
      return (
        entry.offer.title.toLowerCase().includes(needle) ||
        entry.offer.description.toLowerCase().includes(needle) ||
        entry.offer.tags.some((tag) => tag.toLowerCase().includes(needle))
      );
    });
  });

  protected readonly filtered = computed(
    () => this.visible().length !== this.store.entries().length,
  );

  ngOnInit(): void {
    this.dispatch.opened();
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
