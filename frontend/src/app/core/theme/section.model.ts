/**
 * The seven navigation destinations, as the route data names them and as `tokens.css`
 * keys the section colour. One literal union rather than a string: a route that spells a
 * section wrongly fails to type-check instead of painting the neutral fallback quietly.
 *
 * <p>The colour itself is orientation and nothing else: the active nav marker, the accent
 * under the page title, the edge of the section's panels. A primary button never reads it
 * (spec 003, ISC-230).
 */
// `review` is parked (2026-09-24); its colour token stays in `tokens.css`, its screen under `features/review/`.
export const SECTIONS = ['dashboard', 'shortlist', 'pipeline', 'analytics', 'sources', 'rules'] as const;

export type Section = (typeof SECTIONS)[number];

export function isSection(value: unknown): value is Section {
    return typeof value === 'string' && (SECTIONS as readonly string[]).includes(value);
}
