import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CHART_PALETTE, ChartPalette } from '../shared.ports';
import { ChartOption } from './echarts';
import { ChartSurface } from './chart-surface';

/**
 * What is guarded here is the lifecycle, not the picture. An undisposed instance leaves a
 * DOM node and its own animation loop behind, and a chart that never gets its option is a
 * blank box with nothing in the console.
 */
const PALETTE: ChartPalette = {
  primary: 'rgb(14, 110, 107)',
  secondary: 'rgb(10, 78, 76)',
  accent: 'rgb(194, 130, 11)',
  track: 'rgb(231, 226, 216)',
  label: 'rgb(100, 116, 112)',
  surface: 'rgb(255, 255, 255)',
  ink: 'rgb(26, 36, 34)',
  stages: ['a', 'b', 'c', 'd', 'e', 'f', 'g'],
};

@Component({
  imports: [ChartSurface],
  template: `<lg-chart-surface [option]="option()" />`,
})
class Host {
  readonly option = signal<ChartOption>({
    xAxis: { type: 'category', data: ['a', 'b'] },
    yAxis: { type: 'value' },
    series: [{ type: 'bar', data: [1, 2] }],
  });
}

describe('ChartSurface', () => {
  // Provided here rather than taken from core: a spec in `shared/` may no more import
  // from the layers above it than the component it tests, and a fixed palette also keeps
  // the assertions independent of whichever theme happens to be on the document.
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [{ provide: CHART_PALETTE, useValue: signal(PALETTE) }],
    }),
  );

  it('keeps a bar filled while it is hovered', async () => {
    // Measured, not assumed: zrender's colour helper returns undefined for oklch(), so the
    // emphasis lift it computes is undefined and the bar is painted with no fill — the
    // tooltip appears and the bar underneath it disappears. `inherit` is the fix.
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await fixture.whenStable();

    const emphasis = fixture.debugElement.children[0].componentInstance as unknown as {
      normalise(option: ChartOption): { series: { emphasis: { itemStyle: { color: string } } }[] };
    };
    const normalised = emphasis.normalise(fixture.componentInstance.option());

    expect(normalised.series[0].emphasis.itemStyle.color).toBe('inherit');
  });

  it('renders into an element the assistive tree is told to skip', async () => {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await fixture.whenStable();

    const surface: HTMLElement = fixture.nativeElement.querySelector('.surface');
    expect(surface.getAttribute('aria-hidden')).toBe('true');
  });

  it('draws once the element has been laid out', async () => {
    // init measures the element, so it runs in afterNextRender rather than in the
    // constructor. If that ever moves, the chart is created against a zero-sized box.
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('.surface')?.childElementCount).toBeGreaterThan(0);
  });

  it('takes a new option without being recreated', async () => {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await fixture.whenStable();
    const before = fixture.nativeElement.querySelector('.surface');

    fixture.componentInstance.option.set({
      xAxis: { type: 'category', data: ['c'] },
      yAxis: { type: 'value' },
      series: [{ type: 'line', data: [9] }],
    });
    fixture.detectChanges();
    await fixture.whenStable();

    // Same host element: setOption, not dispose-and-init. Recreating would lose the zoom
    // and would make a theme toggle flash.
    expect(fixture.nativeElement.querySelector('.surface')).toBe(before);
  });

  it('empties its element on destroy', async () => {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await fixture.whenStable();
    const surface: HTMLElement = fixture.nativeElement.querySelector('.surface');

    fixture.destroy();

    expect(surface.childElementCount).toBe(0);
  });
});
