/**
 * One row of a ranked bar chart.
 *
 * <p>Declared here rather than imported: `shared/` may not reach into `@core`, so the chart
 * describes the shape it needs and the feature maps its own data onto it. The same reason
 * `funnel-stage.ts` exists beside the funnel rail.
 *
 * @param secondary the part of `value` worth marking — how many of a portal's projects
 *     cleared the filter, say. Drawn inside the bar rather than beside it, because it is a
 *     share of the same number and two bars would invite reading it as a second total.
 */
export interface RankedBar {
  readonly label: string;
  readonly value: number;
  readonly secondary: number;
}
