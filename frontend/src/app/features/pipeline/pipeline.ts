import { ChangeDetectionStrategy, Component, computed } from '@angular/core';
import { APPLICATIONS_FIXTURE } from '@core/fixtures/pipeline.fixture';
import { Application, PIPELINE_LANES, PipelineLane } from '@core/model/application';
import { Badge } from '@shared/badge/badge';
import { PageHeader } from '@shared/page-header/page-header';
import { Score } from '@shared/score/score';

interface Column {
  readonly lane: PipelineLane;
  readonly applications: readonly Application[];
}

@Component({
  selector: 'lg-pipeline',
  imports: [Badge, PageHeader, Score],
  templateUrl: './pipeline.html',
  styleUrl: './pipeline.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Pipeline {
  protected readonly columns = computed<readonly Column[]>(() =>
    PIPELINE_LANES.map((lane) => ({
      lane,
      applications: APPLICATIONS_FIXTURE.filter((application) =>
        lane.states.includes(application.status),
      ),
    })),
  );

  protected toneFor(status: Application['status']): 'success' | 'error' | 'ghost' {
    if (status === 'WON') {
      return 'success';
    }
    return status === 'LOST' || status === 'REJECTED' || status === 'EXPIRED'
      ? 'error'
      : 'ghost';
  }
}
