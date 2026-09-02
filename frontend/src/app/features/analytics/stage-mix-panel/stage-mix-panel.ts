import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { AnalyticsFunnel, ScaleInUse, StageDay } from '@core/model/analytics';
import { Granularity, bucketLabel, startOf } from '../analytics-aggregate';
import { CHART_PALETTE } from '@shared/shared.ports';
import { ChartOption } from '@shared/chart/echarts';
import { ChartSurface } from '@shared/chart/chart-surface';

/**
 * What the filter removes, week by week — and the one panel on this screen that is not a
 * measurement.
 *
 * <p>`filter_stage` is rewritten on every run: the filter re-judges the whole archive by
 * design, because the rules are hot-reloadable and a verdict from an older ruleset would be
 * a verdict nobody can reproduce. So these bars are <b>today's rules applied to the offers
 * that arrived in each week</b>, not what the rules did at the time. The caption says so.
 *
 * <p>Which is a claim the screen can check rather than merely make. If every scored offer
 * in the archive came off one ruleset and one judge, the caveat is currently harmless and
 * the panel says that too. If two appear, the archive already mixes two scales and no
 * comparison across time holds.
 */
@Component({
  selector: 'lg-stage-mix-panel',
  imports: [ChartSurface, TranslocoPipe],
  templateUrl: './stage-mix-panel.html',
  styleUrl: './stage-mix-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StageMixPanel {
  readonly stageMix = input.required<readonly StageDay[]>();
  readonly funnel = input.required<AnalyticsFunnel>();
  readonly scales = input.required<readonly ScaleInUse[]>();
  /** The same one the intake chart uses, so the two never disagree about a bucket. */
  readonly granularity = input.required<Granularity>();

  private readonly palette = inject(CHART_PALETTE);

  /**
   * Days summed into the chosen bucket, here rather than in SQL. Aggregating in two places
   * is how the stage mix ended up showing one weekly bar while the chart above it showed
   * two days of the same archive.
   */
  private readonly bucketed = computed(() => {
    const granularity = this.granularity();
    const summed = new Map<string, number>();
    for (const row of this.stageMix()) {
      const key = `${startOf(row.day, granularity)}|${row.stage}`;
      summed.set(key, (summed.get(key) ?? 0) + row.removed);
    }
    return summed;
  });

  protected readonly buckets = computed(() =>
    [...new Set(this.stageMix().map((row) => startOf(row.day, this.granularity())))].sort(),
  );

  protected readonly mixed = computed(() => this.scales().length > 1);

  protected readonly option = computed<ChartOption>(() => {
    const buckets = this.buckets();
    const colours = this.palette();
    // The stages come from the funnel, in the order the filter runs them. Nothing in the
    // browser holds a second copy of that list.
    const stages = this.funnel().stages;
    const removed = this.bucketed();

    return {
      grid: { top: 16, right: 12, bottom: 24, left: 40, containLabel: true },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      xAxis: {
        type: 'category',
        data: buckets.map((bucket) => bucketLabel(bucket, this.granularity())),
        axisLine: { lineStyle: { color: colours.track } },
        axisLabel: { color: colours.label },
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: colours.track } },
        axisLabel: { color: colours.label },
      },
      series: stages.map((stage, index) => ({
        type: 'bar' as const,
        stack: 'stages',
        name: stage.label,
        // A ramp, not seven hues. They are all rejections, and seven colours would be seven
        // things competing while the one colour that means something loses its meaning.
        itemStyle: { color: colours.stages[index % colours.stages.length] },
        data: buckets.map((bucket) => removed.get(`${bucket}|${stage.id}`) ?? 0),
      })),
    };
  });

  protected readonly rows = computed(() => {
    const removed = this.bucketed();
    return this.buckets().map((bucket) => ({
      bucket,
      label: bucketLabel(bucket, this.granularity()),
      cells: this.funnel().stages.map((stage) => removed.get(`${bucket}|${stage.id}`) ?? 0),
    }));
  });
}
