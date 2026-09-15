import {inject, Pipe, PipeTransform} from '@angular/core';
import {TranslocoService} from '@jsverse/transloco';

/**
 * A calendar day, written the way the chosen language writes one.
 *
 * The server sends a `date` as `YYYY-MM-DD`, and that is what the screen used to print — a
 * machine's spelling, in both languages, beside fields that were otherwise prose. `02.09.2026`
 * and `9/2/2026` are the same day written for two readers, which is the whole reason the
 * locale is the active language rather than a pinned pattern.
 *
 * **Not `DatePipe`, and the difference is not cosmetic.** `date: 'dd.MM.yyyy'` is a literal
 * pattern, so it renders the German form to an English reader; and `DatePipe` parses a bare
 * `YYYY-MM-DD` as UTC midnight and then formats it in the browser's zone, which west of
 * Greenwich shows the day before. The parts are read off the string instead and handed to
 * `Intl` as a local date, so the day that was stored is the day that is shown.
 *
 * Impure, because the answer depends on the active language, which changes while the screen
 * is open. Cheap enough: it is a handful of rows on a detail panel, not a column of a list.
 */
@Pipe({name: 'lgDay', pure: false})
export class DayPipe implements PipeTransform {
  private readonly transloco = inject(TranslocoService);

  transform(value: string | null | undefined): string | null {
    if (value === null || value === undefined || value === '') {
      return null;
    }
    const parts = value.slice(0, 10).split('-').map(Number);
    if (parts.length !== 3 || parts.some((part) => !Number.isFinite(part))) {
      // Whatever it is, it is not a day this pipe knows how to write. Showing it
      // unchanged is honest; returning an empty string would read as "not known" to the
      // template beside it, which is a different claim.
      return value;
    }
    const [year, month, day] = parts;
    return new Intl.DateTimeFormat(this.transloco.getActiveLang(), {dateStyle: 'medium'}).format(
      new Date(year, month - 1, day),
    );
  }
}
