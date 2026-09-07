import {ChangeDetectionStrategy, Component, computed, input, output} from '@angular/core';
import {RouterLink} from '@angular/router';
import {TranslocoPipe} from '@jsverse/transloco';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {Badge} from '@shared/badge/badge';
import {Icon} from '@shared/icon/icon';
import {Score} from '@shared/score/score';

@Component({
    selector: 'lg-offer-card',
    imports: [Badge, Icon, RouterLink, Score, TranslocoPipe],
    templateUrl: './offer-card.html',
    styleUrl: './offer-card.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OfferCard {
    readonly entry = input.required<ShortlistEntry>();

    /**
     * Whether this is the offer the detail column is showing. Passed in from the routed id
     * rather than held here: the URL is what a deep link, the back button and a card click
     * all agree on, and a second copy would disagree with it the first time one is used.
     */
    readonly selected = input(false);

  /**
   * Whether this list offers a selection at all. False on the archive side, where there is
   * no bulk restore — the card is told, it never learns what an archive is.
   */
  readonly pickable = input(false);

  /** Whether this offer is one of the ticked ones. Owned by the store, never by the card. */
  readonly picked = input(false);

  /** While a bulk request is out. One list waits, and the rest of the page stays usable. */
  readonly pickDisabled = input(false);

  /**
   * `output()` and not `model()`: a two-way binding would make the card the writer, and a
   * Shift-range writes cards other than the clicked one.
   */
  readonly pickToggled = output<{ id: number; range: boolean }>();

  /**
   * `(click)` and not `(change)`, because `Event` has no `shiftKey` and `MouseEvent` does —
   * and a keyboard Space on a focused checkbox dispatches a click too, so one handler covers
   * both paths.
   *
   * **`preventDefault()` on every click, so the store stays the only writer of `checked`.**
   * `[checked]` writes only when the bound value changes, and the browser has already
   * flipped the DOM by the time this runs. A Shift-click on an already-ticked card is where
   * the two part company: the browser unticks it, the range logic keeps it ticked, the
   * binding sees `true → true` and has nothing to reconcile, and the box then shows the
   * opposite of the truth.
   */
  protected onPick(event: MouseEvent): void {
    event.preventDefault();
    this.pickToggled.emit({id: this.entry().offer.id, range: event.shiftKey});
  }

    /**
     * The three factors that moved the score most, plus every penalty. A penalty is
     * never hidden behind a cut-off: it is the reason a promising title scored low,
     * and that is exactly what the reader is scanning for.
     */
    protected readonly shownReasons = computed(() => {
        const reasons = this.entry().score.reasons;
        const penalties = reasons.filter((reason) => reason.points < 0);
        const positives = [...reasons.filter((reason) => reason.points > 0)]
            .sort((a, b) => b.points - a.points)
            .slice(0, 3);
        return [...positives, ...penalties];
    });

    /** Everyone advertising this project. One entry means no duplicate cluster. */
    protected readonly otherSources = computed(() => this.entry().sources.slice(1));
}
