import {inject, Pipe, PipeTransform} from '@angular/core';
import {TranslocoService} from '@jsverse/transloco';

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * An instant, as a distance from today in the chosen language: "3 days ago", "vor 3 Tagen".
 *
 * **Days are the coarsest unit, and whole calendar days the measure.** The card this was built
 * for sits on a list that opens newest first, where "45 days ago" compares directly with the
 * card above it and "last month" does not. The count is between local midnights, not a
 * division of milliseconds, so an offer that came in at 23:50 yesterday reads "yesterday" at
 * 00:10 today rather than "today". Anything earlier on the same day is "today" — the hour an
 * offer landed is not what the list is scanned for.
 *
 * `numeric: 'auto'` is what turns one day into "yesterday" and zero into "today", in each
 * language's own words, without a catalog entry of our own.
 *
 * Impure for the same reason `DayPipe` is: the answer depends on the active language, which
 * changes while the screen is open. It also depends on today, but nothing here watches the
 * clock: a screen left open past midnight shows the new count on its next change detection,
 * not at midnight itself.
 */
@Pipe({name: 'lgAgo', pure: false})
export class AgoPipe implements PipeTransform {
  private readonly transloco = inject(TranslocoService);

  transform(value: string | null | undefined): string | null {
    if (value === null || value === undefined || value === '') {
      return null;
    }
    const then = new Date(value);
    if (Number.isNaN(then.getTime())) {
      // Not an instant this pipe can place; showing it unchanged is the honest answer.
      return value;
    }
    const now = new Date();
    const days = Math.round((startOfDay(now) - startOfDay(then)) / DAY_MS);
    return new Intl.RelativeTimeFormat(this.transloco.getActiveLang(), {numeric: 'auto'}).format(
      -days,
      'day',
    );
  }
}

/** Local midnight, so a daylight-saving day of 23 or 25 hours still counts as one. */
function startOfDay(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
}
