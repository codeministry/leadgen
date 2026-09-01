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
  readonly label: string;
  readonly value: string | null;
}

@Component({
  selector: 'lg-offer-detail',
  imports: [ApplicationPanel, Badge, EmptyState, Icon, PageHeader, Score],
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

  protected readonly entry = computed(() =>
    this.store.entries().find((candidate) => candidate.offer.id === this.id()),
  );

  /**
   * An application exists only once a package has been built for the offer, so most
   * offers have none. That absence is the honest state, not an error.
   */
  protected readonly application = computed(() =>
    this.applications
      .applications()
      .find((candidate) => String(candidate.offerId) === this.id()),
  );

  protected readonly history = computed(() => {
    const application = this.application();
    return application === undefined ? [] : (this.applications.history()[application.id] ?? []);
  });

  constructor() {
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
      { label: 'Portal', value: offer.portal },
      { label: 'Agency', value: offer.agency },
      { label: 'Location', value: offer.location },
      { label: 'Remote share', value: offer.remotePercent === null ? null : `${offer.remotePercent} %` },
      { label: 'Rate', value: offer.rateEur === null ? null : `${offer.rateEur} €/h` },
      { label: 'Start', value: offer.startsOn },
      { label: 'Duration', value: offer.duration },
      { label: 'Published', value: offer.publishedOn },
      { label: 'Language', value: offer.language },
      { label: 'External id', value: offer.externalId },
    ];
  });

  /** FIXTURE — the packaging stage (order of work, step 9) will generate this. */
  protected readonly coverLetter = computed(() => {
    const entry = this.entry();
    if (entry === undefined) {
      return '';
    }
    return [
      'Sehr geehrte Damen und Herren,',
      '',
      `Ihre Ausschreibung "${entry.offer.title}" passt genau auf das, woran ich seit über`,
      'zwanzig Jahren arbeite: Java und Spring Boot im Backend, Angular auf der Frontseite,',
      'und der Betrieb auf Kubernetes gleich mit dazu.',
      '',
      'Zwei Referenzen, die dem Vorhaben am nächsten kommen, liegen dem Paket bei.',
      '',
      'Mit freundlichen Grüßen',
      'Marcello Muscara',
    ].join('\n');
  });

  ngOnInit(): void {
    this.dispatch.opened();
    this.applicationDispatch.opened();
  }
}
