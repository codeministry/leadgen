import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { injectDispatch } from '@ngrx/signals/events';
import { TranslocoPipe } from '@jsverse/transloco';
import { applicationEvents } from '@core/store/applications.events';
import { shortlistEvents } from '@core/store/shortlist.events';
import { ShortlistStore } from '@core/store/shortlist.store';
import { ApplicationsStore } from '@core/store/applications.store';
import { IngestStore } from '@core/store/ingest.store';
import { Badge } from '@shared/badge/badge';
import { EmptyState } from '@shared/empty-state/empty-state';
import { FunnelRail } from '@shared/funnel-rail/funnel-rail';
import { Icon } from '@shared/icon/icon';
import { PageHeader } from '@shared/page-header/page-header';
import { StatTile } from '@shared/stat-tile/stat-tile';

@Component({
  selector: 'lg-dashboard',
  imports: [Badge, EmptyState, FunnelRail, Icon, PageHeader, StatTile, TranslocoPipe],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Dashboard implements OnInit {
  private readonly dispatch = injectDispatch(applicationEvents);
  private readonly shortlistDispatch = injectDispatch(shortlistEvents);
  protected readonly ingest = inject(IngestStore);
  protected readonly applications = inject(ApplicationsStore);
  protected readonly shortlist = inject(ShortlistStore);

  /**
   * Counted from `filter_stage` on the offers themselves, which is why the rail can be
   * empty: before the first run there is nothing to count, and a rail showing the
   * measured baseline instead would be a claim about a run that never happened.
   */
  protected readonly stages = computed(() => this.shortlist.funnel()?.stages ?? []);
  protected readonly total = computed(() => this.shortlist.funnel()?.total ?? 0);
  protected readonly survived = computed(() => this.shortlist.funnel()?.survived ?? 0);
  /**
   * Outside the shape, deliberately. Leaving the working list is not something the filter
   * did, and the number is stated because otherwise the rail's total looks wrong: after a
   * week the archive holds most of the table.
   */
  protected readonly archived = computed(() => this.shortlist.funnel()?.archived ?? 0);

  /**
   * A zero and an unreachable board look identical on a tile, and this one is the reason
   * the follow-up dates get entered at all. An em dash says the count is not known.
   */
  protected readonly followUpsDue = computed<number | string>(() =>
    this.applications.error() === null ? this.applications.followUpsDue() : '—',
  );

  ngOnInit(): void {
    this.dispatch.opened();
    this.shortlistDispatch.funnelOpened();
  }

  /**
   * The share of what came in that survived, or nothing to say when nothing came in.
   * A key and its parameters rather than a sentence: no prose is written in TypeScript,
   * and the percentage sits inside the sentence differently in every language.
   */
  protected readonly share = computed(() => {
    const total = this.total();
    return total === 0
      ? { key: 'dashboard.noRunYet', params: {} }
      : {
          key: 'dashboard.shareOfIntake',
          params: { percent: ((this.survived() / total) * 100).toFixed(1) },
        };
  });

  /** Extracted minus written: the same listing seen in two documents. Not deduplication. */
  protected readonly repeats = computed(() => {
    const report = this.ingest.report();
    return report === null ? 0 : report.extracted - report.written;
  });
}
