import { BarChart, LineChart } from 'echarts/charts';
import { GridComponent, MarkLineComponent, TooltipComponent } from 'echarts/components';
import { init, use } from 'echarts/core';
import { SVGRenderer } from 'echarts/renderers';

/**
 * The one file that imports from `echarts`, and the only place the build is decided.
 *
 * <p>Registered piece by piece rather than pulled in whole, exactly as the icons are named
 * imports: the full bundle carries every chart type the library has, and this app draws
 * lines, bars and a histogram.
 *
 * <p><b>SVG, not canvas.</b> A canvas is empty in the accessibility tree, blank in the
 * DOM-render screenshot this repository verifies with, and needs a 2D context that jsdom
 * does not have. SVG has none of those problems, is crisp at any pixel ratio without a
 * `devicePixelRatio` dance, and lands in the same debugging tools as the rest of the app.
 *
 * <p>Registered here is what is drawn today and nothing else. `DataZoom` and `Legend` were
 * in this list and came out again once the chunk was measured: the legend is text the
 * catalog already owns, and a zoom nobody uses is a quarter of a megabyte.
 */
use([LineChart, BarChart, GridComponent, TooltipComponent, MarkLineComponent, SVGRenderer]);

export { init };
export type { EChartsType } from 'echarts/core';

/** Whatever `setOption` accepts. Deliberately opaque: only the callers know the shape. */
export type ChartOption = Parameters<import('echarts/core').EChartsType['setOption']>[0];
