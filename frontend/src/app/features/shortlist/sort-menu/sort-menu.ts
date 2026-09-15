import {ChangeDetectionStrategy, Component, input, output, signal} from '@angular/core';
import {TranslocoPipe} from '@jsverse/transloco';
import {Icon} from '@shared/icon/icon';
import {LgIconName} from '@shared/icon/lucide-icons';
import {AnchorFor} from '@shared/popover/anchor-for';

/**
 * Which way each named order runs, for the arrow beside it.
 *
 * <p>A literal map and never a class or a name assembled at runtime: Tailwind 4 scans source
 * text, and the same discipline keeps this auditable. Keyed by the server's own sort names,
 * which is the one place in this browser that names them — the catalog beside it already has
 * to, because every order needs a sentence, so an order added without an entry here is
 * missing an arrow and not missing a meaning. That is why the fallback is no icon at all:
 * `sort.<key>` says "Highest score" or "Starts soonest", and the direction is in the words.
 */
const DIRECTION: Record<string, LgIconName> = {
  score: 'arrow-down',
  start: 'arrow-up',
  deadline: 'arrow-up',
  duration: 'arrow-down',
  'duration-asc': 'arrow-up',
  fresh: 'arrow-down',
};

/** One id per instance, because a popover is addressed by id and two of these may exist. */
let panels = 0;

/**
 * The order the list is in, as a trigger and a popover.
 *
 * <p><b>Whole named orders and not a field plus a direction toggle.</b> The server keyset-
 * walks with one row comparison, which is legal only while every column of the tuple moves
 * the same way, so a direction is not a modifier over there — it is baked into the key, along
 * with the sentinel that keeps "not stated" at the end. A toggle would therefore be a control
 * that works on some rows and not others, which is the same class as a setting that is
 * rendered, validated and read by nobody.
 *
 * <p>A popover and not `role="menu"`: the contents are one radiogroup, and menuitem semantics
 * would impose a roving tabindex it violates. Not a modal dialog either — trapping focus for
 * "sort by deadline" is too much. The native popover gives light dismiss, Escape and focus
 * return with no script, exactly as the header's settings panel documents.
 */
@Component({
  selector: 'lg-sort-menu',
  imports: [AnchorFor, Icon, TranslocoPipe],
  templateUrl: './sort-menu.html',
  styleUrl: './sort-menu.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SortMenu {
  /** The order in force, as the query string states it. */
  readonly value = input.required<string>();

  /** The server's own sort names, passed in rather than restated here. */
  readonly options = input.required<readonly string[]>();

  readonly picked = output<string>();

  protected readonly panelId = `lg-sort-panel-${++panels}`;

  /**
   * Mirrored from the panel's own `toggle` event rather than tracked on the click, so a light
   * dismiss and an Escape are as visible to a screen reader as the button is.
   */
  protected readonly open = signal(false);

  protected onToggle(event: Event): void {
    this.open.set((event as ToggleEvent).newState === 'open');
  }

  protected arrow(option: string): LgIconName | null {
    return DIRECTION[option] ?? null;
  }

  protected choose(option: string, panel: HTMLElement): void {
    this.picked.emit(option);
    // Closed by hand: a `<button>` inside a popover is not a light dismiss, so without this
    // the panel stays open over a list that is already reordering underneath it.
    panel.hidePopover?.();
  }
}
