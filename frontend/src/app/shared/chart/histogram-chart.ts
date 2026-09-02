import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { CHART_PALETTE } from '../shared.ports';
import { ChartOption } from './echarts';
import { ChartSurface } from './chart-surface';

/** One bucket. `floor` is inclusive; the top bucket also holds the maximum itself. */
export interface HistogramBucket {
  readonly floor: number;
  readonly count: number;
}

/** A line across the chart at a value that decides something. */
export interface HistogramMarker {
  readonly at: number;
  readonly label: string;
}

/**
 * A distribution, with the values that cut it drawn on top.
 *
 * <p>The markers are the point: a histogram of scores says little until you can see where
 * the shortlist threshold falls in it, and whether the mass of the archive sits just below
 * a line somebody chose.
 *
 * <p>Bars above the higher marker take the accent, which still means exactly what it means
 * everywhere else in this app: this cleared the threshold.
 */
@Component({
  selector: 'lg-histogram-chart',
  imports: [ChartSurface],
  templateUrl: './histogram-chart.html',
  styleUrl: './histogram-chart.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HistogramChart {
  readonly buckets = input.required<readonly HistogramBucket[]>();
  readonly markers = input<readonly HistogramMarker[]>([]);
  readonly bucketSize = input(10);
  /** Already-translated column headings; `shared/` holds no catalog keys. */
  readonly bucketLabel = input.required<string>();
  readonly countLabel = input.required<string>();
  readonly caption = input('');

  private readonly palette = inject(CHART_PALETTE);

  protected readonly rows = computed(() =>
    this.buckets().map((bucket) => ({
      ...bucket,
      // The upper bound is inclusive on the last bucket only, which is where a perfect
      // score lands; everywhere else it is one below the next floor.
      to: bucket.floor + this.bucketSize() - 1,
    })),
  );

  protected readonly option = computed<ChartOption>(() => {
    const buckets = this.buckets();
    const colours = this.palette();
    const highest = Math.max(...this.markers().map((marker) => marker.at), Number.NEGATIVE_INFINITY);

    return {
      grid: { top: 24, right: 16, bottom: 24, left: 40, containLabel: true },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: {
        type: 'category',
        data: buckets.map((bucket) => String(bucket.floor)),
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
          data: buckets.map((bucket) => ({
            value: bucket.count,
            itemStyle: { color: bucket.floor >= highest ? colours.accent : colours.primary },
          })),
          markLine: {
            silent: true,
            symbol: 'none',
            lineStyle: { color: colours.label, type: 'dashed' },
            label: { color: colours.label, formatter: '{b}' },
            data: this.markers().map((marker) => ({
              name: marker.label,
              // The axis is categorical, so a threshold is placed on the bucket that holds
              // it rather than at a value the axis does not have.
              xAxis: String(Math.floor(marker.at / this.bucketSize()) * this.bucketSize()),
            })),
          },
        },
      ],
    };
  });
}
