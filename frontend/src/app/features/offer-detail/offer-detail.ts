import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  effect,
  inject,
  input,
} from '@angular/core';
import { injectDispatch } from '@ngrx/signals/events';
import { TranslocoPipe } from '@jsverse/transloco';
import { ApplicationUpdate } from '@core/model/application';
import { applicationEvents } from '@core/store/applications.events';
import { ApplicationsStore } from '@core/store/applications.store';
import { shortlistEvents } from '@core/store/shortlist.events';
import { ShortlistStore } from '@core/store/shortlist.store';
import { ApplicationPanel } from './application-panel/application-panel';
import { Badge } from '@shared/badge/badge';
import { EmptyState } from '@shared/empty-state/empty-state';
import { Icon } from '@shared/icon/icon';
import { PageHeader } from '@shared/page-header/page-header';
import { Score } from '@shared/score/score';

interface Field {
  /** A catalog key, not a sentence. */
  readonly label: string;
  readonly value: string | null;
}

@Component({
  selector: 'lg-offer-detail',
  imports: [ApplicationPanel, Badge, EmptyState, Icon, PageHeader, Score, TranslocoPipe],
  templateUrl: './offer-detail.html',
  styleUrl: './offer-detail.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OfferDetail implements OnInit {
  private readonly dispatch = injectDispatch(shortlistEvents);
  private readonly applicationDispatch = injectDispatch(applicationEvents);
  protected readonly store = inject(ShortlistStore);
  protected readonly applications = inject(ApplicationsStore);

  /** Bound from the route parameter by `withComponentInputBinding()`. */
  readonly id = input.required<string>();

  /**
   * Fetched by id rather than found in the shortlist. The detail has to work on a reload,
   * and it has to open an offer the hard filter rejected — neither of which is in the
   * list.
   */
  protected readonly entry = computed(() => this.store.selected() ?? undefined);

  /**
   * An application exists only once a package has been built for the offer, so most
   * offers have none. That absence is the honest state, not an error.
   */
  protected readonly application = computed(() =>
    this.applications.applications().find((candidate) => String(candidate.offerId) === this.id()),
  );

  protected readonly history = computed(() => {
    const application = this.application();
    return application === undefined ? [] : (this.applications.history()[application.id] ?? []);
  });

  constructor() {
    // The id comes from the URL, so it changes without the component being recreated.
    effect(() => {
      const id = Number(this.id());
      if (Number.isFinite(id)) {
        this.dispatch.offerRequested(id);
      }
    });

    // The store replaces the row after every save, so this re-reads the log exactly when
    // there is something new in it — and never while the board is merely being scrolled.
    effect(() => {
      const application = this.application();
      if (application !== undefined) {
        this.applicationDispatch.historyRequested(application.id);
      }
    });
  }

  protected record(update: ApplicationUpdate): void {
    const application = this.application();
    if (application !== undefined) {
      this.applicationDispatch.changed({ id: application.id, update });
    }
  }

  /**
   * Every extracted field, including the ones that came back empty. A missing
   * rate is information — it means enrichment did not reach the original ad —
   * and hiding the row would make the gap invisible.
   */
  protected readonly fields = computed<readonly Field[]>(() => {
    const offer = this.entry()?.offer;
    if (offer === undefined) {
      return [];
    }
    return [
      { label: 'field.portal', value: offer.portal },
      { label: 'field.agency', value: offer.agency },
      { label: 'field.location', value: offer.location },
      { label: 'field.remoteShare', value: offer.remotePercent === null ? null : `${offer.remotePercent} %` },
      { label: 'field.rate', value: offer.rateEur === null ? null : `${offer.rateEur} €/h` },
      { label: 'field.start', value: offer.startsOn },
      { label: 'field.duration', value: offer.duration },
      { label: 'field.workload', value: offer.workload },
      { label: 'field.published', value: offer.publishedOn },
      { label: 'field.language', value: offer.language },
      { label: 'field.externalId', value: offer.externalId },
    ];
  });

  ngOnInit(): void {
    this.applicationDispatch.opened();
  }
}
