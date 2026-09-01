import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ShortlistEntry } from '@core/model/shortlist-entry';
import { Badge } from '@shared/badge/badge';
import { Icon } from '@shared/icon/icon';
import { Score } from '@shared/score/score';

@Component({
  selector: 'lg-offer-card',
  imports: [Badge, Icon, RouterLink, Score],
  templateUrl: './offer-card.html',
  styleUrl: './offer-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OfferCard {
  readonly entry = input.required<ShortlistEntry>();

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
