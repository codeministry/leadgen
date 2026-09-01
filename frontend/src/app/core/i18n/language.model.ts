/**
 * What the reader chose. `system` means: take the browser's own language, which is what
 * an unset preference has always meant on this shell — the theme reads `system` the same
 * way.
 */
export type LanguagePreference = 'system' | Language;

/** A catalog that exists under `public/i18n/`. Adding one is adding a file and a name. */
export type Language = 'en' | 'de';

/**
 * English is the fallback, not German, because English is this repository's language: a
 * key nobody translated yet shows the sentence that was written rather than a blank.
 */
export const FALLBACK_LANGUAGE: Language = 'en';

export const AVAILABLE_LANGUAGES: readonly Language[] = ['en', 'de'];

/**
 * Kept in step by hand with the inline script in `src/index.html`, which reads the same
 * key before Angular boots — not to avoid a flash of the wrong language (there is nothing
 * painted yet to be wrong) but so `<html lang>` is right for the first screen reader and
 * the first hyphenation pass.
 */
export const LANGUAGE_STORAGE_KEY = 'lg-language';

export function isLanguage(value: unknown): value is Language {
  return AVAILABLE_LANGUAGES.includes(value as Language);
}

export function isLanguagePreference(value: unknown): value is LanguagePreference {
  return value === 'system' || isLanguage(value);
}

/**
 * `de-AT` and `de` are the same catalog here. A region nobody wrote a file for has to
 * resolve to the language, or every reader outside Germany silently gets English.
 */
export function languageOf(tag: string | null | undefined): Language | null {
  const base = (tag ?? '').toLowerCase().split('-')[0];
  return isLanguage(base) ? base : null;
}
