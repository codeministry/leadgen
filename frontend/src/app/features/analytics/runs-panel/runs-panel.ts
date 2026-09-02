import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { RunSeries } from '@core/model/analytics';
import { CHART_PALETTE } from '@shared/shared.ports';
import { ChartOption } from '@shared/chart/echarts';
import { ChartSurface } from '@shared/chart/chart-surface';

/**
 * What each run actually did.
 *
 * <p>The only panel here that is a record rather than a reading. Every other number on this
 * screen is computed from the archive as it stands now, and the archive is rewritten on
 * every run; these rows were written down while they were still true and nothing touches
 * them again.
 *
 * <p>Which is also why the panel states when the history begins. It cannot be backfilled —
 * the evidence for every run before the table existed was overwritten — and a chart that
 * silently starts three weeks ago implies that nothing happened before.
 */
@Component({
  selector: 'lg-runs-panel',
  imports: [ChartSurface, TranslocoPipe],
  templateUrl: './runs-panel.html',
  styleUrl: './runs-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunsPanel {
  readonly runs = input.required<RunSeries>();

  private readonly palette = inject(CHART_PALETTE);
  private readonly transloco = inject(TranslocoService);

  /**
   * The reader's own clock.
   *
   * <p>`finishedAt` arrives as an ISO instant in UTC, and slicing sixteen characters off it
   * showed a run at 08:47 as 06:47 — every other time on this screen is local, so the one
   * that was not read as though the pipeline ran two hours earlier than it did. The locale
   * follows the chosen language rather than being pinned, because 09/02 and 02.09. are the
   * same day written for two readers.
   */
  private formatter(): Intl.DateTimeFormat {
    return new Intl.DateTimeFormat(this.transloco.getActiveLang(), {
      dateStyle: 'short',
      timeStyle: 'short',
    });
  }

  /**
   * All five runs on one day give five identical date labels. When the runs span a single
   * day the axis carries the time instead, which is the part that differs.
   */
  protected readonly rows = computed(() => {
    const passes = this.runs().passes;
    const format = this.formatter();
    const oneDay =
      new Set(passes.map((pass) => pass.finishedAt.slice(0, 10))).size <= 1 && passes.length > 1;
    const axis = oneDay
      ? new Intl.DateTimeFormat(this.transloco.getActiveLang(), { timeStyle: 'short' })
      : format;
    return passes.map((pass) => ({
      pass,
      when: format.format(new Date(pass.finishedAt)),
      axis: axis.format(new Date(pass.finishedAt)),
    }));
  });

  protected readonly since = computed(() => this.runs().historySince?.slice(0, 10) ?? null);

  protected readonly option = computed<ChartOption>(() => {
    const passes = this.runs().passes;
    const colours = this.palette();

    return {
      grid: { top: 16, right: 12, bottom: 24, left: 40, containLabel: true },
      tooltip: { trigger: 'axis' },
      xAxis: {
        type: 'category',
        data: this.rows().map((row) => row.axis),
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
          type: 'line',
          data: passes.map((pass) => pass.extracted),
          itemStyle: { color: colours.primary },
          lineStyle: { color: colours.primary },
        },
        {
          type: 'line',
          data: passes.map((pass) => pass.filterPassed),
          itemStyle: { color: colours.accent },
          lineStyle: { color: colours.accent },
        },
      ],
    };
  });
}
