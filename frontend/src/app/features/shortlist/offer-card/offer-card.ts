import {NgTemplateOutlet} from '@angular/common';
import {ChangeDetectionStrategy, Component, computed, input, output} from '@angular/core';
import {RouterLink} from '@angular/router';
import {TranslocoPipe} from '@jsverse/transloco';
import {ListDensity} from '@core/density/density.model';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {ApplicationStatus, statusLabel} from '@core/model/application';
import {ScoreReason} from '@core/model/score';
import {Score} from '@shared/score/score';
import {AgoPipe} from '@shared/date/ago.pipe';
import {Icon} from '@shared/icon/icon';
import {LgIconName} from '@shared/icon/lucide-icons';
import {DayPipe} from '@shared/date/day.pipe';

/**
 * One item of the facts line. A union rather than a pre-translated string, because the
 * remote share and the start/deadline phrases go through the catalog, and a `computed` that
 * translated them would not notice the language changing under it — the pipe in the
 * template does.
 */
export type CardFact =
  | {readonly kind: 'text'; readonly icon: LgIconName | null; readonly text: string}
  | {readonly kind: 'remote'; readonly percent: number}
  | {readonly kind: 'start' | 'deadline'; readonly when: string};

/**
 * A reason label as the card shows it. The skill overlap's label is written by the scorer as
 * `<up to eight skills>[ and N more] (skill weight X, a full match is Y)` — 80 to 120
 * characters with digits, which on one line pushed the penalty and the topics out of sight. The card
 * drops the trailing parenthetical and the "and N more" count; the detail keeps both.
 */
export function cardLabel(label: string): string {
  return label
    .replace(/\s*\([^()]*\)\s*$/, '')
    .replace(/\s+and \d+ more$/, ' …')
    .trim();
}

/**
 * The shape beside each status word. A literal map rather than a name built from the status,
 * so every icon is spelled out where the registry and a reader can find it, and a new status
 * fails the type check here instead of rendering an empty square. The board shows no status
 * icons, so there is nothing to stay consistent with yet; if it grows some, it takes these.
 */
export const STATUS_ICONS: Readonly<Record<ApplicationStatus, LgIconName>> = {
  NEW: 'circle-dot',
  SHORTLISTED: 'star',
  PACKAGED: 'package',
  SENT: 'send',
  REPLIED: 'message-square-reply',
  INTERVIEW: 'users',
  OFFER: 'handshake',
  WON: 'trophy',
  LOST: 'circle-x',
  REJECTED: 'ban',
  EXPIRED: 'hourglass',
};

@Component({
    selector: 'lg-offer-card',
  imports: [AgoPipe, DayPipe, Icon, NgTemplateOutlet, RouterLink, Score, TranslocoPipe],
    templateUrl: './offer-card.html',
    styleUrl: './offer-card.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OfferCard {
    readonly entry = input.required<ShortlistEntry>();

  /**
   * How much of the card to show. The page reads the density store and hands it down, the way
   * it hands down `pickable`: the card never reads a store, so it stays a function of its
   * inputs. `compact` is two lines — the title row, then the known facts with the strongest
   * lift — and the topics, the penalty, the source line and the "not scored" sentence are the
   * comfortable card's and the detail's. A flag keeps its warning triangle in both: a warning that only
   * shows in one density is one the reader learns to miss.
   */
  readonly density = input<ListDensity>('comfortable');
  protected readonly compact = computed(() => this.density() === 'compact');

  /**
   * The ring's edge in pixels. 44 on the comfortable card; 36 in compact, where the card is
   * two lines of text and a 44px ring would set its height on its own. ⟨?: 36px in compact —
   * the fog of whether compact drops the ring for the number alone is decided in the browser⟩
   */
  protected readonly ringSize = computed(() => (this.compact() ? 36 : 44));

  /** Either flag set: the one warning triangle the compact line keeps. */
  protected readonly flagged = computed(() => {
    const {flags} = this.entry();
    return flags.possibleDuplicate || flags.incomplete;
  });

  /**
   * What the advert actually said, in a fixed order: rate, location, duration, remote share,
   * then the start and the deadline. A value the advert never carried is simply not there —
   * no "rate unknown" slot, which read on nearly every card because the newsletter carries a
   * rate in 0.0 % of offers, and no separator hanging off a missing neighbour. The detail
   * panel still names every field, known or not.
   */
  protected readonly knownFacts = computed<readonly CardFact[]>(() => {
    const {offer, flags} = this.entry();
    const facts: CardFact[] = [];
    // A rate of 0 is no rate: the old card treated it as unknown, and so does this one.
    if (offer.rateEur !== null && offer.rateEur !== 0) {
      facts.push({kind: 'text', icon: null, text: `${offer.rateEur} €/h`});
    }
    if (offer.location) {
      facts.push({kind: 'text', icon: 'map-pin', text: offer.location});
    }
    if (offer.duration) {
      facts.push({kind: 'text', icon: 'clock', text: offer.duration});
    }
    if (!flags.remoteUnknown && offer.remotePercent !== null) {
      facts.push({kind: 'remote', percent: offer.remotePercent});
    }
    const start = offer.startText ?? offer.startsOn;
    if (start) {
      facts.push({kind: 'start', when: start});
    }
    const deadline = offer.applyByText ?? offer.applyBy;
    if (deadline) {
      facts.push({kind: 'deadline', when: deadline});
    }
    return facts;
  });

    /**
     * Whether this is the offer the detail column is showing. Passed in from the routed id
     * rather than held here: the URL is what a deep link, the back button and a card click
     * all agree on, and a second copy would disagree with it the first time one is used.
     */
    readonly selected = input(false);
    /** The application's status, shown on every card whether it is the open one or not. */
    readonly status = input<ApplicationStatus | null>(null);
    protected readonly statusView = computed<{ readonly icon: LgIconName; readonly text: string } | null>(() => {
        const status = this.status();
        return status === null ? null : {icon: STATUS_ICONS[status], text: statusLabel(status)};
    });

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
   * The outliers, not the scoreboard: the one factor that lifted the score most and the one
   * that held it back most, as bare labels shortened for the card. The detail column keeps
   * every reason with its full label and points; the card only has to say why at a glance.
   *
   * Interest topics are left out of the lift, because they are named on their own — a bonus
   * of twelve would lose the one lift slot to the skill overlap every time. A disinterest
   * topic (a topic row with negative points) is a penalty like any other and competes for the penalty slot.
   */
  protected readonly strongestLift = computed<string | null>(() => {
    const best = this.entry()
      .score.reasons.filter((reason) => reason.points > 0 && reason.topic == null)
      .reduce<ScoreReason | null>((top, reason) => (top === null || reason.points > top.points ? reason : top), null);
    return best === null ? null : cardLabel(best.label);
  });

  protected readonly strongestPenalty = computed<string | null>(() => {
    const worst = this.entry()
      .score.reasons.filter((reason) => reason.points < 0)
      .reduce<ScoreReason | null>((low, reason) => (low === null || reason.points < low.points ? reason : low), null);
    // A disinterest row is named by its topic: its label spells "disinterest (judged): …", and
    // the glyph already says it held the score back.
    return worst === null ? null : worst.topic ? worst.topic : cardLabel(worst.label);
  });

  /** Every interest topic that lifted the score, by name. */
  protected readonly topicNames = computed<readonly string[]>(() =>
    this.entry()
      .score.reasons.filter((reason) => reason.points > 0)
      .map((reason) => reason.topic)
      .filter((topic): topic is string => topic != null && topic !== ''),
  );

  /** Nothing to say about why: the "not scored" line stands in, as it did before. */
  protected readonly hasWhy = computed(
    () => this.strongestLift() !== null || this.strongestPenalty() !== null || this.topicNames().length > 0,
  );

  /**
   * Where it is advertised, in one word: the agency when the advert names one, the portal
   * otherwise. The names of the other sources are the detail column's business; the card
   * only counts them.
   */
  protected readonly sourceWord = computed<string | null>(() => {
    const {offer, sources} = this.entry();
    const first = sources[0];
    return offer.agency ?? offer.portal ?? first?.agency ?? first?.portal ?? null;
  });

  /** How many other sources advertise the same project. Zero means no duplicate cluster. */
  protected readonly otherSourceCount = computed(() => Math.max(0, this.entry().sources.length - 1));
}
