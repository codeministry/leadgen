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

/**
 * Each theme's surface (`--color-base-100`) as the hex twin `src/styles.css` writes beside
 * the `oklch()` value. The manifest's `theme_color` and `background_color` and the
 * `theme-color` meta take their value from here, because a manifest and a meta tag cannot
 * read a custom property. `theme-colors.spec.ts` holds both to the stylesheet.
 */
export const THEME_SURFACE_HEX: Record<ResolvedTheme, string> = {
    'lg-light': '#FFFFFF',
    'lg-dark': '#1F222D',
};

export function isThemePreference(value: unknown): value is ThemePreference {
    return value === 'system' || value === 'light' || value === 'dark';
}
