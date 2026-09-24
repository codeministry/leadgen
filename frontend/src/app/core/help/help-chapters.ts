import {isSection, Section} from '@core/theme/section.model';

/**
 * The help's table of contents: one chapter per screen, and one that explains how the
 * screens work together.
 *
 * <p>This list is the seam the help is built on (spec 010). The chapter texts live as
 * Markdown under `public/help/{en,de}/<id>.md`, the diagrams as one theme-token SVG each under
 * `public/help/diagrams/<id>.svg`, and the titles and captions in the catalogs
 * as `help.chapter.<id>` and `help.diagram.<id>`. A chapter or a diagram added here without
 * its file and its key is caught by the help specs, not by a reader.
 *
 * <p>`review` has a chapter although the screen is parked: the chapter costs nothing while
 * the screen is away, and the screen coming back should not have to remember its help.
 */
export const HELP_CHAPTERS = [
    'how-it-works',
    'dashboard',
    'shortlist',
    'pipeline',
    'analytics',
    'rules',
    'sources',
    'review',
] as const;

export type HelpChapter = (typeof HELP_CHAPTERS)[number];

export const HELP_DIAGRAMS = ['run-phases', 'parts', 'application-states'] as const;

export type HelpDiagram = (typeof HELP_DIAGRAMS)[number];

/** The chapter a screen opens the help at. Every section is a screen with a chapter. */
export const SECTION_CHAPTER: Record<Section, HelpChapter> = {
    dashboard: 'dashboard',
    shortlist: 'shortlist',
    pipeline: 'pipeline',
    analytics: 'analytics',
    sources: 'sources',
    rules: 'rules',
};

/**
 * Which diagrams a chapter shows, in the order they are read. Only the overview carries
 * any: a screen chapter says what the screen is for, the overview says how they fit.
 */
export const CHAPTER_DIAGRAMS: Record<HelpChapter, readonly HelpDiagram[]> = {
    'how-it-works': HELP_DIAGRAMS,
    dashboard: [],
    shortlist: [],
    pipeline: [],
    analytics: [],
    rules: [],
    sources: [],
    review: [],
};

/** A route without a section — the redirect still resolving, an unknown path — opens the overview. */
export function chapterForSection(section: unknown): HelpChapter {
    return isSection(section) ? SECTION_CHAPTER[section] : 'how-it-works';
}

export function isHelpDiagram(value: unknown): value is HelpDiagram {
    return typeof value === 'string' && (HELP_DIAGRAMS as readonly string[]).includes(value);
}

export function isHelpChapter(value: unknown): value is HelpChapter {
    return typeof value === 'string' && (HELP_CHAPTERS as readonly string[]).includes(value);
}
