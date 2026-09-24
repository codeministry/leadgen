import {ChangeDetectionStrategy, Component, computed, input, output, signal} from '@angular/core';
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
  score: 'arrow-down-wide-narrow',
  'score-asc': 'arrow-up-narrow-wide',
  start: 'arrow-up-narrow-wide',
  'start-desc': 'arrow-down-wide-narrow',
  deadline: 'arrow-up-narrow-wide',
  'deadline-desc': 'arrow-down-wide-narrow',
  duration: 'arrow-down-wide-narrow',
  'duration-asc': 'arrow-up-narrow-wide',
  fresh: 'arrow-down-wide-narrow',
  'fresh-asc': 'arrow-up-narrow-wide',
};

/**
 * One order and its reverse, both as the server names them. A pair rather than a direction
 * flag, because over the wire the reverse is a key of its own with a sentinel of its own —
 * see `ShortlistSort` — and the browser only has to know which two belong together.
 */
export interface SortOption {
  readonly key: string;
  readonly reverse: string;
}

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
  readonly options = input.required<readonly SortOption[]>();

  readonly picked = output<string>();

  protected readonly panelId = `lg-sort-panel-${++panels}`;

  /**
   * Mirrored from the panel's own `toggle` event rather than tracked on the click, so a light
   * dismiss and an Escape are as visible to a screen reader as the button is.
   */
  protected readonly open = signal(false);

  /** The pair the current order belongs to, whichever of its two directions is on. */
  protected readonly current = computed(() =>
    this.options().find((option) => option.key === this.value() || option.reverse === this.value()),
  );

  protected readonly reversed = computed(() => this.current()?.reverse === this.value());

  protected onToggle(event: Event): void {
    this.open.set((event as ToggleEvent).newState === 'open');
  }

  protected arrow(option: string): LgIconName | null {
    return DIRECTION[option] ?? null;
  }

  /** The same order the other way round, "not stated" still last. */
  protected flip(): void {
    const current = this.current();
    if (current !== undefined) {
      this.picked.emit(this.reversed() ? current.key : current.reverse);
    }
  }

  /** An order is picked in the direction it reads naturally; the toggle beside it turns it. */
  protected choose(option: string, panel: HTMLElement): void {
    this.picked.emit(option);
    // Closed by hand: a `<button>` inside a popover is not a light dismiss, so without this
    // the panel stays open over a list that is already reordering underneath it.
    panel.hidePopover?.();
  }
}
