import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

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
  /** `scoring.thresholds.auto_shortlist` from matching-rules.yaml. */
  readonly shortlistAt = input(70);
  /** Below this the offer is discarded rather than put up for review. */
  readonly reviewAt = input(50);
  readonly size = input(56);

  protected readonly circumference = CIRCUMFERENCE;
  protected readonly radius = RADIUS;

  protected readonly band = computed<ScoreBand>(() => {
    const value = this.value();
    if (value === null) {
      return 'unscored';
    }
    if (value >= this.shortlistAt()) {
      return 'strong';
    }
    return value >= this.reviewAt() ? 'weak' : 'out';
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
