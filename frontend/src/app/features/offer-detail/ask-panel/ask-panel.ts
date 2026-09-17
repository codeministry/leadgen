import {ChangeDetectionStrategy, Component, computed, inject, input, signal} from '@angular/core';
import {HttpErrorResponse} from '@angular/common/http';
import {TranslocoPipe} from '@jsverse/transloco';
import {ShortlistApi} from '@core/api/shortlist.api';
import {ADVERT_QUESTIONS, AdvertAnswer, AdvertQuestion} from '@core/model/advert-answer';
import {Icon} from '@shared/icon/icon';

/**
 * Five questions an advert is read for, answered from the advert and nothing else.
 *
 * <p><b>Every answer carries a sentence from the advert.</b> The server drops any claim whose
 * quote it could not find in the text, so what reaches here is checkable by looking up — and
 * the quote is rendered, not hidden behind a tooltip, because an answer nobody can check is an
 * answer nobody should act on.
 *
 * <p>Three states and they are deliberately not one. **Silent** is an answer: this advert does
 * not say. **Refused** is not: nobody could ask, because no model is configured, the advert was
 * never fetched, or the day's budget is spent. Drawn the same way, a reader would read a spent
 * budget as a quiet advert. **Answered** carries the sentence and its quote.
 *
 * <p>The answers live as long as this component. Nothing is stored, so nothing goes stale when
 * the content stage rewrites the advert underneath it.
 */
@Component({
  selector: 'lg-ask-panel',
  imports: [Icon, TranslocoPipe],
  templateUrl: './ask-panel.html',
  styleUrl: './ask-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AskPanel {
  private readonly api = inject(ShortlistApi);

  readonly offerId = input.required<number>();

  /** The five, in the order they are worth asking. The server holds the same list. */
  protected readonly questions = ADVERT_QUESTIONS;

  private readonly answers = signal<Record<string, AdvertAnswer>>({});
  private readonly refusals = signal<Record<string, string>>({});
  private readonly pending = signal<string | null>(null);

  protected readonly asking = computed(() => this.pending());

  protected answerFor(question: AdvertQuestion): AdvertAnswer | undefined {
    return this.answers()[question];
  }

  protected refusalFor(question: AdvertQuestion): string | undefined {
    return this.refusals()[question];
  }

  /**
   * One question, one request, and never two at once: the budget is shared with the judge, and
   * a reader holding a key down must not spend five calls on one advert.
   */
  protected ask(question: AdvertQuestion): void {
    if (this.pending() !== null || this.answerFor(question) !== undefined) {
      return;
    }
    this.pending.set(question);
    this.api.ask(this.offerId(), question).subscribe({
      next: (answer) => {
        this.answers.update((all) => ({...all, [question]: answer}));
        // A question that now has an answer has no refusal: the two are mutually exclusive,
        // and leaving the old one would draw both under the same question.
        this.refusals.update((all) =>
          Object.fromEntries(Object.entries(all).filter(([key]) => key !== question)),
        );
        this.pending.set(null);
      },
      // The server's own sentence, not one invented here: it names which of the three
      // reasons applied, and the screen has no business restating it.
      error: (error: HttpErrorResponse) => {
        this.refusals.update((all) => ({...all, [question]: String(error.error ?? '')}));
        this.pending.set(null);
      },
    });
  }
}
