import {existsSync, readdirSync, readFileSync} from 'node:fs';
import {resolve} from 'node:path';

/**
 * The help chapters exist in both languages, and none of them says more than a user needs.
 *
 * <p>It reads the Markdown from disk rather than through the drawer, because the questions
 * are about the files. The drawer falls back to nothing for a chapter one language lacks,
 * so a missing German file is a blank drawer on the one screen nobody opened in German. And
 * the help ships in a public repository, so a URL, an endpoint or a file name in it is a
 * leak the reader cannot act on anyway: the chapters are for a versed user, not a developer.
 */

const HELP = resolve(process.cwd(), 'public/help');
const LANGUAGES = ['en', 'de'] as const;

const CHAPTERS = ['dashboard', 'shortlist', 'pipeline', 'analytics', 'rules', 'sources', 'review', 'how-it-works'];

/** Where the drawer inserts a figure, in the order the chapter reads them. */
const DIAGRAMS = ['run-phases', 'parts', 'application-states'];

/** What a help text never names: a URL, an endpoint, a file, a class, a table, the product. */
const FORBIDDEN: readonly [string, RegExp][] = [
    ['a URL', /https?:|www\./i],
    ['an endpoint', /\/api\//i],
    ['a file name', /\.(java|kts?|ts|js|mjs|ya?ml|json|sql|md|eml|env|txt|html|css|svg|properties)\b/i],
    ['a class name', /\b[A-Z][a-z]+(?:[A-Z][a-z]+)+\b/],
    ['a table or key name', /\b[a-z]+_[a-z_]+\b/],
    ['an environment variable', /\b[A-Z]{2,}_[A-Z_]+\b/],
    ['the product name', /lead-?gen/i],
];

function chapterIds(language: string): string[] {
    const dir = resolve(HELP, language);
    if (!existsSync(dir)) {
        return [];
    }
    return readdirSync(dir)
        .filter((name) => name.endsWith('.md'))
        .map((name) => name.slice(0, -'.md'.length))
        .sort();
}

function read(language: string, chapter: string): string {
    return readFileSync(resolve(HELP, language, `${chapter}.md`), 'utf8');
}

/** The diagram ids a chapter places, in order, as `<!-- diagram: id -->` lines. */
function placeholders(text: string): string[] {
    return [...text.matchAll(/^<!-- diagram: ([a-z-]+) -->$/gm)].map((m) => m[1]);
}

/** The text with the placeholders removed, since a diagram id is not prose. */
function prose(text: string): string {
    return text.replace(/^<!-- diagram: [a-z-]+ -->$/gm, '');
}

describe('help chapters', () => {
    it('hold the same chapter ids in English and German, and exactly the expected ones', () => {
        const expected = [...CHAPTERS].sort();

        expect(chapterIds('en')).toEqual(expected);
        expect(chapterIds('de')).toEqual(expected);
    });

    it('place the three diagrams in how-it-works, in the same order in both languages', () => {
        for (const language of LANGUAGES) {
            expect(placeholders(read(language, 'how-it-works')), language).toEqual(DIAGRAMS);
        }
    });

    it('place no diagram in a screen chapter', () => {
        for (const language of LANGUAGES) {
            for (const chapter of CHAPTERS.filter((id) => id !== 'how-it-works')) {
                expect(placeholders(read(language, chapter)), `${language}/${chapter}`).toEqual([]);
            }
        }
    });

    it('leave the heading to the drawer and are not empty', () => {
        for (const language of LANGUAGES) {
            for (const chapter of CHAPTERS) {
                const text = read(language, chapter);
                expect(text.startsWith('#'), `${language}/${chapter}: the drawer renders the chapter title`).toBe(false);
                expect(text.trim().split('\n').length, `${language}/${chapter}`).toBeGreaterThan(2);
            }
        }
    });

    it('name no URL, endpoint, file, class, table, variable or product', () => {
        const hits: string[] = [];
        for (const language of LANGUAGES) {
            for (const chapter of chapterIds(language)) {
                const text = prose(read(language, chapter));
                for (const [what, pattern] of FORBIDDEN) {
                    const match = pattern.exec(text);
                    if (match) {
                        hits.push(`${language}/${chapter}: ${what} (“${match[0]}”)`);
                    }
                }
            }
        }

        expect(hits).toEqual([]);
    });
});
