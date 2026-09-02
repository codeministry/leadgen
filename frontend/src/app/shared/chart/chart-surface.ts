import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  DOCUMENT,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
  viewChild,
} from '@angular/core';
import { CHART_PALETTE } from '../shared.ports';
import { ChartOption, EChartsType, init } from './echarts';

/**
 * One chart instance, and nothing else.
 *
 * <p>The option object is opaque here on purpose: this component owns the lifecycle and
 * the callers own the meaning. Everything about *what* is drawn lives in the chart
 * components above it, which also render the text equivalent — so a reader without the
 * picture still gets the numbers, and the two cannot drift because they come from one
 * input.
 *
 * <p>Four lifecycle rules, each of them a failure this repository has already paid for
 * somewhere else:
 *
 * <ul>
 *   <li><b>Init in `afterNextRender`.</b> `init` measures the element, and an element with
 *       no size yet gives a chart with no size ever.
 *   <li><b>Update in an `effect`.</b> The option is a signal, so data changes and theme
 *       changes arrive the same way and there is one code path for both. Guarded, because
 *       the effect can run before the render callback.
 *   <li><b>`resize` inside a frame.</b> Calling it straight from the `ResizeObserver`
 *       callback re-triggers the observer and fills the console with "loop completed with
 *       undelivered notifications".
 *   <li><b>Dispose through `DestroyRef`.</b> An undisposed instance leaves a DOM node and
 *       its own animation loop running.
 * </ul>
 */
@Component({
  selector: 'lg-chart-surface',
  templateUrl: './chart-surface.html',
  styleUrl: './chart-surface.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChartSurface {
  /** What `setOption` gets. Replaced wholesale; never merged away. */
  readonly option = input.required<ChartOption>();

  /** Chart height in rem, because the root font size is 93.75 % and everything scales. */
  readonly height = input(18);

  private readonly surface = viewChild.required<ElementRef<HTMLDivElement>>('surface');
  private readonly view = inject(DOCUMENT).defaultView;
  private readonly palette = inject(CHART_PALETTE);
  private chart: EChartsType | null = null;
  private observer: ResizeObserver | null = null;

  constructor() {
    afterNextRender(() => {
      const element = this.surface().nativeElement;
      this.chart = init(element, undefined, { renderer: 'svg' });
      this.chart.setOption(this.normalise(this.option()));
      this.observeSize(element);
    });

    effect(() => {
      const option = this.option();
      this.chart?.setOption(this.normalise(option));
    });

    inject(DestroyRef).onDestroy(() => {
      this.observer?.disconnect();
      this.chart?.dispose();
      this.chart = null;
    });
  }

  /**
   * Three things every chart needs and none of them should have to remember.
   *
   * <p><b>The emphasis colour is set to `inherit`, and that is a bug fix rather than a
   * preference.</b> On hover ECharts lightens the item's own colour through zrender's
   * colour helper, and that helper does not understand `oklch()` — measured:
   * `parse('oklch(48.78% 0.0805 191.43)')` returns `undefined`, so `lift` returns
   * `undefined` and the bar is painted with no fill at all. The tooltip appears and the
   * bar vanishes underneath it. `inherit` keeps the fill; the tooltip and the axis pointer
   * are what say "this one".
   *
   * <p><b>The tooltip is themed here too</b>, because otherwise it is the library's own
   * white box, which on the dark theme is the brightest thing on the page.
   *
   * <p><b>Reduced motion switches the animation off</b> rather than shortening it, the
   * same rule the funnel rail follows. `matchMedia` is guarded: it is absent in jsdom.
   */
  private normalise(option: ChartOption): ChartOption {
    const colours = this.palette();
    const reduced =
      typeof this.view?.matchMedia === 'function' &&
      this.view.matchMedia('(prefers-reduced-motion: reduce)').matches;

    // Cast rather than typed: the option is deliberately opaque here — only the chart
    // components above know its shape — and this reaches for two fields common to all.
    const source = option as { series?: unknown; tooltip?: object };
    const series = Array.isArray(source.series) ? source.series : undefined;

    return {
      ...(option as object),
      ...(reduced ? { animation: false } : {}),
      ...(source.tooltip
        ? {
            tooltip: {
              backgroundColor: colours.surface,
              borderColor: colours.track,
              textStyle: { color: colours.ink },
              ...source.tooltip,
            },
          }
        : {}),
      ...(series
        ? {
            series: series.map((one) => ({
              emphasis: { itemStyle: { color: 'inherit' } },
              ...(one as object),
            })),
          }
        : {}),
    } as ChartOption;
  }

  private observeSize(element: HTMLElement): void {
    if (typeof ResizeObserver !== 'function') {
      return;
    }
    this.observer = new ResizeObserver((entries) => {
      // The observer fires once on observe, sometimes before layout. A resize to zero
      // collapses the chart and it never comes back on its own.
      if (entries[0].contentRect.width <= 0) {
        return;
      }
      this.view?.requestAnimationFrame(() => this.chart?.resize());
    });
    this.observer.observe(element);
  }
}
