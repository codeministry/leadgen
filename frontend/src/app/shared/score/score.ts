import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { SCORE_THRESHOLDS } from '../shared.ports';

export type ScoreBand = 'strong' | 'weak' | 'out' | 'unscored';

const RADIUS = 20;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

/**
 * A score with its band. `value` is null when the pipeline ran without a language
 * model — the shortlist still exists then, only unranked, and an empty ring says
 * that more honestly than a zero would.
 */
@Component({
  selector: 'lg-score',
  templateUrl: './score.html',
  styleUrl: './score.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Score {
  readonly value = input.required<number | null>();
  readonly size = input(56);

  /**
   * The two thresholds, injected rather than passed. They used to be inputs defaulting to
   * 70 and 50, and not one of the three callers ever overrode them — so every ring in the
   * app banded off a constant while the rules screen showed the configured numbers. One
   * source, reached through the seam `shared/` is allowed to use.
   */
  private readonly thresholds = inject(SCORE_THRESHOLDS);

  protected readonly circumference = CIRCUMFERENCE;
  protected readonly radius = RADIUS;

  protected readonly band = computed<ScoreBand>(() => {
    const value = this.value();
    if (value === null) {
      return 'unscored';
    }
    if (value >= this.thresholds().shortlistAt) {
      return 'strong';
    }
    return value >= this.thresholds().reviewAt ? 'weak' : 'out';
  });

  protected readonly dashOffset = computed(() => {
    const value = this.value();
    if (value === null) {
      return CIRCUMFERENCE;
    }
    return CIRCUMFERENCE * (1 - Math.max(0, Math.min(100, value)) / 100);
  });

  protected readonly label = computed(() => {
    const value = this.value();
    return value === null ? 'Not scored' : `Score ${value} of 100`;
  });
}
