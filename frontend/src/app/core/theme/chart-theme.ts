import { DOCUMENT, Provider, Signal, effect, inject, signal } from '@angular/core';
import { CHART_PALETTE, ChartPalette } from '@shared/shared.ports';
import { ThemeStore } from './theme.store';

/** Seven, because there are seven filter stages and each needs its own step. */
const STAGE_STEPS = 7;

const FALLBACK: ChartPalette = {
  primary: '#0E6E6B',
  secondary: '#0A4E4C',
  accent: '#C2820B',
  track: '#E7E2D8',
  label: '#647470',
  surface: '#FFFFFF',
  ink: '#1A2422',
  stages: Array.from({ length: STAGE_STEPS }, () => '#B9C2BF'),
};

/**
 * The theme's colours, as the flat strings a charting library can take.
 *
 * <p>This is the seam between the two themes and a library that knows nothing about them.
 * The values are never written here — they are read back off the document element, so the
 * one place a colour is declared stays `styles.css` and a change there reaches the charts
 * without anything else being edited.
 *
 * <p><b>It reads one frame late, and that is the whole trick.</b> `data-theme` is written
 * by the theme store's own effect, and `getComputedStyle` called in the same turn can
 * still return the previous theme's values — the same family as the recorded trap where a
 * backgrounded tab returns a transition's start value instead of its target. So this is not
 * a `computed`: it is an effect on the resolved theme that re-reads the tokens inside one
 * animation frame and writes a signal.
 *
 * <p>The fallback exists for the test environment, where `getComputedStyle` returns nothing
 * for a custom property. It is the only place in the app besides `styles.css` that names a
 * colour, and it names them as hex on purpose: these are never painted, they only keep a
 * chart from being drawn in `undefined`.
 */
export function provideChartPalette(): Provider {
  return {
    provide: CHART_PALETTE,
    useFactory: (): Signal<ChartPalette> => {
      const document = inject(DOCUMENT);
      const view = document.defaultView;
      const theme = inject(ThemeStore);
      const palette = signal<ChartPalette>(read(document, FALLBACK));

      effect(() => {
        // Depended on so the effect re-runs on a toggle; the value itself is not used,
        // because the tokens are what actually carry the theme.
        theme.theme();
        const apply = () => palette.set(read(document, palette()));
        if (typeof view?.requestAnimationFrame === 'function') {
          view.requestAnimationFrame(apply);
        } else {
          apply();
        }
      });

      return palette.asReadonly();
    },
  };
}

function read(document: Document, previous: ChartPalette): ChartPalette {
  const style = document.defaultView?.getComputedStyle(document.documentElement);
  const token = (name: string, fallback: string) =>
    style?.getPropertyValue(name).trim() || fallback;

  return {
    primary: token('--color-primary', previous.primary),
    secondary: token('--color-secondary', previous.secondary),
    accent: token('--color-accent', previous.accent),
    track: token('--color-base-300', previous.track),
    label: token('--lg-muted', previous.label),
    surface: token('--color-base-100', previous.surface),
    ink: token('--color-base-content', previous.ink),
    stages: previous.stages.map((fallback, index) =>
      token(`--lg-chart-stage-${index + 1}`, fallback),
    ),
  };
}
