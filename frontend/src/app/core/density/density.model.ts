/**
 * How much of a list fits on screen. `comfortable` is the card with its four bands,
 * `compact` two lines a card. A display preference of this browser, like the theme: never in
 * the URL and never in a saved view, because a shared link says what to look at, not how
 * tightly.
 */
export type ListDensity = 'comfortable' | 'compact';

export const DENSITY_STORAGE_KEY = 'lg-list-density';

export const DEFAULT_DENSITY: ListDensity = 'comfortable';

export function isListDensity(value: unknown): value is ListDensity {
  return value === 'comfortable' || value === 'compact';
}
