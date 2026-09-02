import { Signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { CHART_PALETTE, ChartPalette } from '@shared/shared.ports';
import { provideChartPalette } from './chart-theme';
import { themeEvents } from './theme.events';
import { DATA_THEME_ATTR, THEME_STORAGE_KEY } from './theme.model';
import { ThemeStore } from './theme.store';
import { Dispatcher } from '@ngrx/signals/events';

/**
 * The palette is the seam between two themes and a library that cannot read a custom
 * property. What is guarded here is that it follows the theme at all: without this, the
 * dark theme draws its charts in the light palette until somebody reloads the page.
 */
describe('provideChartPalette', () => {
  const root = document.documentElement;

  function paint(primary: string): void {
    root.style.setProperty('--color-primary', primary);
  }

  beforeEach(() => {
    localStorage.removeItem(THEME_STORAGE_KEY);
    root.removeAttribute(DATA_THEME_ATTR);
    root.style.removeProperty('--color-primary');
    TestBed.configureTestingModule({ providers: [provideChartPalette()] });
  });

  afterEach(() => root.style.removeProperty('--color-primary'));

  it('reads the colours off the document rather than carrying its own', async () => {
    paint('rgb(1, 2, 3)');
    const palette: Signal<ChartPalette> = TestBed.inject(CHART_PALETTE);
    await frame();

    expect(palette().primary).toBe('rgb(1, 2, 3)');
  });

  it('follows a theme change, one frame later', async () => {
    paint('rgb(1, 2, 3)');
    const palette: Signal<ChartPalette> = TestBed.inject(CHART_PALETTE);
    TestBed.inject(ThemeStore);
    await frame();

    // The store writes data-theme in its own effect, and getComputedStyle read in the same
    // turn can still answer with the previous theme — which is why the palette waits a
    // frame rather than being a plain computed.
    paint('rgb(9, 9, 9)');
    TestBed.inject(Dispatcher).dispatch(themeEvents.chosen('dark'));
    TestBed.tick();
    await frame();

    expect(palette().primary).toBe('rgb(9, 9, 9)');
  });

  it('answers with something drawable when the tokens resolve to nothing', () => {
    // jsdom has no stylesheet, and neither does a chart rendered before the fonts land.
    // A palette of empty strings would draw a chart in `undefined`.
    const palette: Signal<ChartPalette> = TestBed.inject(CHART_PALETTE);

    expect(palette().stages).toHaveLength(7);
    expect(palette().primary.length).toBeGreaterThan(0);
  });
});

function frame(): Promise<void> {
  return new Promise((resolve) => requestAnimationFrame(() => resolve()));
}
