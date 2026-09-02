import { HttpClient } from '@angular/common/http';
import { EnvironmentProviders, Provider, inject, isDevMode } from '@angular/core';
import { Translation, TranslocoLoader, provideTransloco } from '@jsverse/transloco';
import { provideTranslocoMessageformat } from '@jsverse/transloco-messageformat';
import { AVAILABLE_LANGUAGES, FALLBACK_LANGUAGE } from './language.model';

/**
 * The catalogs are static files under `public/i18n/`, fetched by the same HttpClient the
 * rest of the app uses. They are deliberately not bundled: a translation fixed at five in
 * the afternoon should not need a rebuild of the application to reach the browser.
 */
class CatalogLoader implements TranslocoLoader {
  private readonly http = inject(HttpClient);

  getTranslation(language: string) {
    return this.http.get<Translation>(`/i18n/${language}.json`);
  }
}

export function provideI18n(): (Provider | EnvironmentProviders)[] {
  return [
    provideTransloco({
      config: {
        availableLangs: [...AVAILABLE_LANGUAGES],
        defaultLang: FALLBACK_LANGUAGE,
        // English is the fallback because English is this repository's language: a key
        // nobody translated shows the sentence that was written rather than a blank.
        fallbackLang: FALLBACK_LANGUAGE,
        missingHandler: { useFallbackTranslation: true },
        // The language is chosen by `LanguageStore`, which knows about `system` and the
        // stored preference. Letting Transloco pick as well would mean two answers.
        reRenderOnLangChange: true,
        prodMode: !isDevMode(),
      },
      loader: CatalogLoader,
    }),
    // ICU plurals. "1 listings" is the kind of wrong that only ever shows up on the one
    // day a run finds exactly one, and German declines differently from English anyway —
    // a rule per language belongs in the catalog, not in a ternary in a template.
    provideTranslocoMessageformat(),
  ];
}
