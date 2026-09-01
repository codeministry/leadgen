import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { injectDispatch } from '@ngrx/signals/events';
import { FILTER_STAGES_FIXTURE, FILTER_TOTAL_FIXTURE } from '@core/fixtures/funnel.fixture';
import { applicationEvents } from '@core/store/applications.events';
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
  imports: [Badge, EmptyState, FunnelRail, Icon, PageHeader, StatTile],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Dashboard implements OnInit {
  private readonly dispatch = injectDispatch(applicationEvents);
  protected readonly ingest = inject(IngestStore);
  protected readonly applications = inject(ApplicationsStore);

  protected readonly stages = FILTER_STAGES_FIXTURE;
  protected readonly total = FILTER_TOTAL_FIXTURE;

  protected readonly survived = computed(() =>
    this.stages.reduce((left, stage) => left - stage.removed, this.total),
  );

  /**
   * A zero and an unreachable board look identical on a tile, and this one is the reason
   * the follow-up dates get entered at all. An em dash says the count is not known.
   */
  protected readonly followUpsDue = computed<number | string>(() =>
    this.applications.error() === null ? this.applications.followUpsDue() : '—',
  );

  ngOnInit(): void {
    this.dispatch.opened();
  }

  /** Extracted minus written: the same listing seen in two documents. Not deduplication. */
  protected readonly repeats = computed(() => {
    const report = this.ingest.report();
    return report === null ? 0 : report.extracted - report.written;
  });
}
