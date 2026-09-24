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
 * <p>The parked review screen has no chapter while it has no route (spec 014): a chapter about a
 * screen nobody can open is a promise the app does not keep. It comes back with the screen.
 */
export const HELP_CHAPTERS = [
    'dashboard',
    'shortlist',
    'offer-detail',
    'application',
    'filters-views',
    'pipeline',
    'analytics',
    'rules',
    'sources',
    'app-basics',
    'how-it-works',
] as const;

export type HelpChapter = (typeof HELP_CHAPTERS)[number];

export const HELP_DIAGRAMS = ['run-phases', 'parts', 'application-states'] as const;

export type HelpDiagram = (typeof HELP_DIAGRAMS)[number];

/** The pixel size a screenshot file is written at; the drawer reserves it before the file loads. */
export interface HelpShotSize {
    readonly width: number;
    readonly height: number;
}

/**
 * The screenshots, each taken from the demo stack by `bun run help:shots` in both languages and
 * both themes: `public/help/shots/<lang>/<id>-<light|dark>.webp`. A raster image cannot follow
 * the theme the way the diagrams' tokens do, so the drawer picks the file that matches what
 * the reader is looking at. The size is stated here rather than read from the file, so the
 * figure has its height before the image arrives and the text under it does not jump.
 */
export const HELP_SHOTS = {
    'run-phases-rail': {width: 1400, height: 678},
    dashboard: {width: 1400, height: 788},
    'shortlist-split': {width: 1400, height: 788},
    'pipeline-board': {width: 1400, height: 788},
    'analytics-overview': {width: 1400, height: 788},
    'rules-stage': {width: 1400, height: 788},
    'sources-panel': {width: 1400, height: 788},
    'offer-why-scored': {width: 1320, height: 1120},
    'offer-ask': {width: 1320, height: 760},
    'application-panel': {width: 1320, height: 1040},
    'cover-letter': {width: 1320, height: 1040},
    'filters-panel': {width: 1240, height: 1120},
    'sort-menu': {width: 1040, height: 840},
    'run-confirm': {width: 1120, height: 560},
    'settings-panel': {width: 720, height: 640},
} as const satisfies Record<string, HelpShotSize>;

export type HelpShot = keyof typeof HELP_SHOTS;

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
    'offer-detail': [],
    application: [],
    'filters-views': [],
    'app-basics': [],
};

/**
 * Which screenshots a chapter shows, in the order they are read. Unlike the diagrams every
 * chapter carries its own: a screen chapter is easier to follow beside a picture of the screen.
 */
export const CHAPTER_SHOTS: Record<HelpChapter, readonly HelpShot[]> = {
    'how-it-works': ['run-phases-rail'],
    dashboard: ['dashboard'],
    shortlist: ['shortlist-split'],
    pipeline: ['pipeline-board'],
    analytics: ['analytics-overview'],
    rules: ['rules-stage'],
    sources: ['sources-panel'],
    'offer-detail': ['offer-why-scored', 'offer-ask'],
    application: ['application-panel', 'cover-letter'],
    'filters-views': ['filters-panel', 'sort-menu'],
    'app-basics': ['run-confirm', 'settings-panel'],
};

/** A route without a section — the redirect still resolving, an unknown path — opens the overview. */
export function chapterForSection(section: unknown): HelpChapter {
    return isSection(section) ? SECTION_CHAPTER[section] : 'how-it-works';
}

/** The two screens whose `:id` child is an offer; the sources' child is a source. */
const OFFER_SECTIONS: readonly unknown[] = ['shortlist', 'pipeline'];

/**
 * The chapter for where the reader is: an offer open under the shortlist or the pipeline is read
 * about in the offer-detail chapter, everything else in its screen's.
 */
export function chapterForRoute(section: unknown, offerOpen: boolean): HelpChapter {
    return offerOpen && OFFER_SECTIONS.includes(section) ? 'offer-detail' : chapterForSection(section);
}

export function isHelpDiagram(value: unknown): value is HelpDiagram {
    return typeof value === 'string' && (HELP_DIAGRAMS as readonly string[]).includes(value);
}

export function isHelpShot(value: unknown): value is HelpShot {
    return typeof value === 'string' && Object.hasOwn(HELP_SHOTS, value);
}

export function isHelpChapter(value: unknown): value is HelpChapter {
    return typeof value === 'string' && (HELP_CHAPTERS as readonly string[]).includes(value);
}
