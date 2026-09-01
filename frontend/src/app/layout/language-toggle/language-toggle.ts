import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { injectDispatch } from '@ngrx/signals/events';
import { languageEvents } from '@core/i18n/language.events';
import { LanguagePreference } from '@core/i18n/language.model';
import { LanguageStore } from '@core/i18n/language.store';

interface LanguageOption {
  readonly preference: LanguagePreference;
  /** A catalog key for the accessible name, and a short code for the button face. */
  readonly label: string;
  readonly code: string;
}

/**
 * The same three states as the theme toggle, and deliberately the same control: `system`
 * is a state of its own rather than the absence of a choice, so the reader can see that
 * English is on screen *because the browser asked for it*.
 *
 * <p>The face is a two-letter code rather than a flag. A flag names a country and a
 * language is not one — the German catalog serves Austria and Switzerland too.
 */
@Component({
  selector: 'lg-language-toggle',
  imports: [TranslocoPipe],
  templateUrl: './language-toggle.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LanguageToggle {
  protected readonly store = inject(LanguageStore);
  private readonly dispatch = injectDispatch(languageEvents);

  protected readonly options: readonly LanguageOption[] = [
    { preference: 'system', label: 'language.system', code: 'auto' },
    { preference: 'en', label: 'language.en', code: 'EN' },
    { preference: 'de', label: 'language.de', code: 'DE' },
  ];

  protected choose(preference: LanguagePreference): void {
    this.dispatch.chosen(preference);
  }
}
