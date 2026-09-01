import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input } from '@angular/core';
import { injectDispatch } from '@ngrx/signals/events';
import { shortlistEvents } from '@core/store/shortlist.events';
import { ShortlistStore } from '@core/store/shortlist.store';
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
  imports: [Badge, EmptyState, Icon, PageHeader, Score],
  templateUrl: './offer-detail.html',
  styleUrl: './offer-detail.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OfferDetail implements OnInit {
  private readonly dispatch = injectDispatch(shortlistEvents);
  protected readonly store = inject(ShortlistStore);

  /** Bound from the route parameter by `withComponentInputBinding()`. */
  readonly id = input.required<string>();

  protected readonly entry = computed(() =>
    this.store.entries().find((candidate) => candidate.offer.id === this.id()),
  );

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
  }
}
