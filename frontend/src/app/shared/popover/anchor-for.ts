import {Directive, ElementRef, inject, input} from '@angular/core';

/**
 * Puts a popover panel under the control that opens it.
 *
 * <p>A popover lives in the top layer, whose containing block is the viewport whatever
 * `position` says — so `position: absolute` inside the row that holds the trigger does not
 * follow it, and the panel would resolve against the window instead. The header's settings
 * panel solves that with arithmetic, because its trigger is pinned to the shell's right edge
 * and cannot move. A filter trigger can: it sits in a column whose position depends on the
 * width, on the language and on what wrapped, so the only honest answer is to read where it
 * actually is.
 *
 * <p>Two custom properties and not `left`/`top` directly, so the stylesheet decides whether
 * to use them. Below 48rem both panels are bottom sheets, and a real property written inline
 * would beat the media query that makes them one.
 *
 * <p>On `click` and not on the panel's `toggle`: a listener runs during dispatch and the
 * popover opens in the activation behaviour afterwards, so the position is already written
 * when the panel is first painted. Measured once per open, deliberately — a panel is a
 * transient choice, and following the trigger through a scroll would mean an observer for a
 * box that is on screen for two seconds.
 */
@Directive({
  selector: '[lgAnchorFor]',
  host: {'(click)': 'place()'},
})
export class AnchorFor {
  /** The panel to place. The template reference of the element carrying `popover`. */
  readonly panel = input.required<HTMLElement>({alias: 'lgAnchorFor'});

  private readonly trigger = inject<ElementRef<HTMLElement>>(ElementRef);

  protected place(): void {
    const rect = this.trigger.nativeElement.getBoundingClientRect();
    const style = this.panel().style;
    style.setProperty('--lg-anchor-x', `${Math.round(rect.left)}px`);
    style.setProperty('--lg-anchor-y', `${Math.round(rect.bottom + 4)}px`);
  }
}
