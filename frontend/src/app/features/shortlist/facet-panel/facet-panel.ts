import {ChangeDetectionStrategy, Component, input, output, signal} from '@angular/core';
import {TranslocoPipe} from '@jsverse/transloco';
import {Icon} from '@shared/icon/icon';
import {AnchorFor} from '@shared/popover/anchor-for';

/** One id per instance, because a popover is addressed by id. */
let panels = 0;

/**
 * Everything the filter bar does not show at rest, behind one trigger.
 *
 * <p>Five facets in a 22rem panel rather than five controls in a 36rem column, and the width
 * is the whole argument: every row of filters is a row of list, and the panel has the vertical
 * room to give each control a visible label where the column had to make the value stand in
 * for its category. "Within 30 days" beside a heading that says Start is a filter; the same
 * words alone in a bare select are a riddle.
 *
 * <p>Presentational: it emits what was chosen and writes nothing. The page owns the query
 * string, which is what keeps the rule that a filtered view is a link true in one place.
 */
@Component({
  selector: 'lg-facet-panel',
  imports: [AnchorFor, Icon, TranslocoPipe],
  templateUrl: './facet-panel.html',
  styleUrl: './facet-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FacetPanel {
  /** Every portal the shortlist knows, the server's list and never the loaded page's. */
  readonly portals = input.required<readonly string[]>();
  readonly selectedPortals = input.required<readonly string[]>();

  readonly minScore = input.required<number | null>();
  readonly maxScore = input.required<number | null>();
  readonly scoreState = input.required<string>();

  readonly startWindow = input.required<string>();
  readonly startWindowOptions = input.required<readonly string[]>();

  readonly minMonths = input.required<number>();
  readonly minMonthsOptions = input.required<readonly number[]>();

  readonly deadlineOpen = input.required<boolean>();
  readonly possibleDuplicates = input.required<boolean>();

  /** How many of the five are on. The badge on the trigger, and the chips agree with it. */
  readonly activeCount = input.required<number>();

  readonly portalToggled = output<string>();
  readonly scoreRangeChanged = output<{ readonly min: number | null; readonly max: number | null }>();
  readonly scoreStateChanged = output<string>();
  readonly startWindowChanged = output<string>();
  readonly minMonthsChanged = output<number>();
  readonly deadlineToggled = output<void>();
  readonly possibleDuplicatesToggled = output<void>();

  protected readonly panelId = `lg-facet-panel-${++panels}`;
  protected readonly open = signal(false);

  protected readonly scoreStates = ['any', 'scored', 'unscored'] as const;

  protected onToggle(event: Event): void {
    this.open.set((event as ToggleEvent).newState === 'open');
  }

  protected isPicked(name: string): boolean {
    return this.selectedPortals().includes(name);
  }

  /**
   * A bound as the query string will carry it: a number, or null for "no bound".
   *
   * <p>Null and not zero, because zero is a score an offer can actually have — the same
   * distinction `minMonths` does not need, where zero genuinely means no minimum. Anything
   * that is not a number is also null: a field somebody cleared is a bound removed.
   */
  private static bound(value: string): number | null {
    const trimmed = value.trim();
    if (trimmed === '') {
      return null;
    }
    const score = Number(trimmed);
    return Number.isFinite(score) ? score : null;
  }

  /**
   * `change` and never `input`: a number field fires `input` per keystroke and per step, and
   * every one of those is a navigation and a request. The blur is when the reader is done.
   */
  protected onMin(event: Event): void {
    this.scoreRangeChanged.emit({
      min: FacetPanel.bound((event.target as HTMLInputElement).value),
      max: this.maxScore(),
    });
  }

  protected onMax(event: Event): void {
    this.scoreRangeChanged.emit({
      min: this.minScore(),
      max: FacetPanel.bound((event.target as HTMLInputElement).value),
    });
  }

  protected onStartWindow(event: Event): void {
    this.startWindowChanged.emit((event.target as HTMLSelectElement).value);
  }

  protected onMinMonths(event: Event): void {
    this.minMonthsChanged.emit(Number((event.target as HTMLSelectElement).value));
  }
}
