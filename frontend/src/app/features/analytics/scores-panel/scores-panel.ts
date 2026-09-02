import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ScoreDistribution } from '@core/model/analytics';
import { HistogramChart, HistogramMarker } from '@shared/chart/histogram-chart';

/**
 * How the scores are spread, and where the two thresholds cut it.
 *
 * <p>The thresholds come down with the payload rather than being restated here, so the
 * chart and the file that decides them cannot disagree. What the picture is for is the
 * question no single score answers: whether the mass of the archive sits just under a line
 * somebody chose, which is an argument about the threshold and not about the offers.
 *
 * <p>Unscored is its own number, outside the histogram. It is not a bucket, and it is not
 * zero: it is an offer whose deterministic reasons were written and whose total was
 * withheld, because a total from five of nine weights is not comparable to one from all
 * nine.
 */
@Component({
  selector: 'lg-scores-panel',
  imports: [HistogramChart, TranslocoPipe],
  templateUrl: './scores-panel.html',
  styleUrl: './scores-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScoresPanel {
  readonly scores = input.required<ScoreDistribution>();

  private readonly transloco = inject(TranslocoService);

  protected readonly scored = computed(() =>
    this.scores().buckets.reduce((sum, bucket) => sum + bucket.count, 0),
  );

  protected readonly markers = computed<readonly HistogramMarker[]>(() => [
    { at: this.scores().reviewAt, label: this.transloco.translate('analytics.markerReview') },
    { at: this.scores().shortlistAt, label: this.transloco.translate('analytics.markerShortlist') },
  ]);

  protected readonly labels = computed(() => ({
    bucket: this.transloco.translate('analytics.colScore'),
    count: this.transloco.translate('analytics.colOffers'),
  }));
}
