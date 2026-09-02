import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { CHART_PALETTE } from '@shared/shared.ports';
import { ChartOption } from '@shared/chart/echarts';
import { ChartSurface } from '@shared/chart/chart-surface';
import { Granularity, IntakeBucket, bucketLabel } from '../analytics-aggregate';

/**
 * Offers over time — the number this screen exists for.
 *
 * <p>The chart and the table below it are built from one input, so the picture and its text
 * equivalent cannot drift apart. The surface itself is `aria-hidden`; the table is what a
 * screen reader, a DOM-render screenshot and anyone who prefers numbers actually gets.
 *
 * <p>The bar's height is a measurement and its lower segment is not: an arrival date is
 * written once and never rewritten, while "passed" is what today's rules say about that
 * day's offers. The caption says so, because nothing in the numbers can.
 */
@Component({
  selector: 'lg-intake-panel',
  imports: [ChartSurface, TranslocoPipe],
  templateUrl: './intake-panel.html',
  styleUrl: './intake-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IntakePanel {
  readonly buckets = input.required<readonly IntakeBucket[]>();
  readonly granularity = input.required<Granularity>();

  /** The bucket's own span. A week labelled with its Monday alone reads as a single day. */
  protected readonly rows = computed(() =>
    this.buckets().map((bucket) => ({
      ...bucket,
      label: bucketLabel(bucket.day, this.granularity()),
    })),
  );

  private readonly palette = inject(CHART_PALETTE);
  private readonly transloco = inject(TranslocoService);

  protected readonly option = computed<ChartOption>(() => {
    const buckets = this.buckets();
    const colours = this.palette();

    return {
      // No title and no legend inside the chart: both are text the page already carries in
      // the catalog, and a string rendered by the library bypasses Transloco entirely.
      grid: { top: 16, right: 12, bottom: 28, left: 48, containLabel: true },
      tooltip: { trigger: 'axis' },
      xAxis: {
        type: 'category',
        data: buckets.map((bucket) => bucket.day),
        axisLine: { lineStyle: { color: colours.track } },
        axisLabel: { color: colours.label },
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: colours.track } },
        axisLabel: { color: colours.label },
      },
      series: [
        {
          type: 'bar',
          stack: 'intake',
          name: this.transloco.translate('analytics.rejected'),
          data: buckets.map((bucket) => bucket.primaries - bucket.passed),
          itemStyle: { color: colours.track },
        },
        {
          type: 'bar',
          stack: 'intake',
          name: this.transloco.translate('analytics.passed'),
          data: buckets.map((bucket) => bucket.passed),
          itemStyle: { color: colours.primary },
        },
      ],
    };
  });

  protected readonly total = computed(() =>
    this.buckets().reduce((sum, bucket) => sum + bucket.primaries, 0),
  );

  protected readonly passed = computed(() =>
    this.buckets().reduce((sum, bucket) => sum + bucket.passed, 0),
  );
}
