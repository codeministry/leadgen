import { InjectionToken, Signal } from '@angular/core';

/**
 * The colours a chart draws with, resolved from the theme's own custom properties.
 *
 * <p>A charting library cannot read a CSS custom property — it takes literal strings — so
 * something has to resolve them, and that something has to live above `shared/` because it
 * needs the theme store. This interface is the shape of the answer; the token below is how
 * it arrives.
 */
export interface ChartPalette {
  /** Structure and the first series. Petrol in the light theme, the logo's cyan in dark. */
  readonly primary: string;
  readonly secondary: string;
  /** Ochre. Still means one thing: this cleared the threshold. Never a label colour. */
  readonly accent: string;
  /** Bar tracks, grid lines, the axis itself. */
  readonly track: string;
  /** Text-safe. The fills fail contrast at label sizes, which is why this is separate. */
  readonly label: string;
  readonly surface: string;
  /** Body text on `surface`. What a tooltip is written in. */
  readonly ink: string;
  /**
   * Seven steps for the seven rejection stages, darkest-last in the light theme and
   * darkest-first in the dark one. A ramp rather than seven hues, because they are all
   * rejections and nothing among them deserves to stand out.
   */
  readonly stages: readonly string[];
}

export const CHART_PALETTE = new InjectionToken<Signal<ChartPalette>>('lg.chart.palette');
