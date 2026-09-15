/**
 * A filter view is a name and a query string. That is the whole model, and it is the point:
 * the URL stays the truth about what is on screen, and a view is a link with a name on it.
 *
 * <p>Anything richer — a structured copy of the eight filters — would be a second
 * representation of the same state, free to disagree with the query string the first time a
 * filter is added. Stored as the string, a view written before this month's filter existed
 * still applies exactly as it was saved.
 */
export interface FilterView {
  readonly id: string;
  readonly name: string;
  /** The query string without its `?`, exactly as the router wrote it. */
  readonly query: string;
}

export const FILTER_VIEWS_STORAGE_KEY = 'lg-filter-views';

/** How long a name may be. A chip in a popover, not a paragraph. */
export const FILTER_VIEW_NAME_MAX = 40;

/**
 * What came back out of storage, checked rather than trusted.
 *
 * <p>The key is a string in somebody's browser: it survives across versions of this app, it
 * can be edited by hand, and it can be half-written by a tab that was closed mid-save. A
 * shape check here is what stops any of that reaching a template as `undefined`.
 */
export function isFilterView(value: unknown): value is FilterView {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Partial<Record<keyof FilterView, unknown>>;
  return (
    typeof candidate.id === 'string' &&
    typeof candidate.name === 'string' &&
    typeof candidate.query === 'string'
  );
}
