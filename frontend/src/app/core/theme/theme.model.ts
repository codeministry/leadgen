/** What the reader chose. `system` means: no `data-theme`, let the media query decide. */
export type ThemePreference = 'system' | 'light' | 'dark';

/** What ends up on `<html data-theme>`. Must match the `name:` of the two daisyUI themes. */
export type ResolvedTheme = 'lg-light' | 'lg-dark';

/**
 * Kept in step by hand with the inline script in `src/index.html`, which reads the
 * same key before Angular boots so the page never paints in the wrong theme.
 */
export const THEME_STORAGE_KEY = 'lg-theme';

export const DATA_THEME_ATTR = 'data-theme';

export function isThemePreference(value: unknown): value is ThemePreference {
  return value === 'system' || value === 'light' || value === 'dark';
}
