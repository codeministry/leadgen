import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { FILTER_STAGES_FIXTURE, FILTER_TOTAL_FIXTURE } from '@core/fixtures/funnel.fixture';
import { APPLICATIONS_FIXTURE } from '@core/fixtures/pipeline.fixture';
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
export class Dashboard {
  protected readonly ingest = inject(IngestStore);

  protected readonly stages = FILTER_STAGES_FIXTURE;
  protected readonly total = FILTER_TOTAL_FIXTURE;

  protected readonly survived = computed(() =>
    this.stages.reduce((left, stage) => left - stage.removed, this.total),
  );

  protected readonly followUpsDue = computed(
    () => APPLICATIONS_FIXTURE.filter((application) => application.followUpOn !== null).length,
  );

  /** Extracted minus written: the same listing seen in two documents. Not deduplication. */
  protected readonly repeats = computed(() => {
    const report = this.ingest.report();
    return report === null ? 0 : report.extracted - report.written;
  });
}
