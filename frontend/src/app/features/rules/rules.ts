import { ChangeDetectionStrategy, Component, computed } from '@angular/core';
import { RULES_FIXTURE } from '@core/fixtures/rules.fixture';
import { Badge } from '@shared/badge/badge';
import { Icon } from '@shared/icon/icon';
import { PageHeader } from '@shared/page-header/page-header';

@Component({
  selector: 'lg-rules',
  imports: [Badge, Icon, PageHeader],
  templateUrl: './rules.html',
  styleUrl: './rules.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Rules {
  protected readonly rules = RULES_FIXTURE;

  /** Weights are an open map in matching-rules.yaml, so the bar is relative to the largest. */
  protected readonly maxWeight = computed(() =>
    Math.max(...this.rules.weights.map((weight) => weight.points), 1),
  );

  protected readonly maxPenalty = computed(() =>
    Math.max(...this.rules.penalties.map((penalty) => Math.abs(penalty.points)), 1),
  );
}
