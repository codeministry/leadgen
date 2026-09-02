import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { CHART_PALETTE } from '../shared.ports';
import { ChartOption } from './echarts';
import { ChartSurface } from './chart-surface';
import { RankedBar } from './ranked-bar';

/**
 * A ranked list as horizontal bars, with the same numbers as a table beneath it.
 *
 * <p>Horizontal because the labels are names — a portal, a tag, a town — and a vertical bar
 * chart with fifteen names underneath it turns them all sideways.
 *
 * <p>Highest at the top, which in ECharts means reversing the array: its category axis
 * counts from the bottom, so a list handed over in rank order draws upside down.
 */
@Component({
  selector: 'lg-ranked-bar-chart',
  imports: [ChartSurface],
  templateUrl: './ranked-bar-chart.html',
  styleUrl: './ranked-bar-chart.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RankedBarChart {
  readonly bars = input.required<readonly RankedBar[]>();
  /** Column headings for the table. Already translated: `shared/` holds no catalog keys. */
  readonly valueLabel = input.required<string>();
  readonly secondaryLabel = input.required<string>();
  readonly caption = input('');

  private readonly palette = inject(CHART_PALETTE);

  protected readonly height = computed(() => Math.max(8, this.bars().length * 1.6 + 2));

  protected readonly option = computed<ChartOption>(() => {
    const bars = [...this.bars()].reverse();
    const colours = this.palette();

    return {
      grid: { top: 8, right: 16, bottom: 8, left: 8, containLabel: true },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: colours.track } },
        axisLabel: { color: colours.label },
      },
      yAxis: {
        type: 'category',
        data: bars.map((bar) => bar.label),
        axisLine: { lineStyle: { color: colours.track } },
        axisLabel: { color: colours.label },
      },
      series: [
        {
          type: 'bar',
          stack: 'ranked',
          data: bars.map((bar) => bar.secondary),
          itemStyle: { color: colours.accent },
        },
        {
          type: 'bar',
          stack: 'ranked',
          data: bars.map((bar) => bar.value - bar.secondary),
          itemStyle: { color: colours.track },
        },
      ],
    };
  });
}
