import {readdirSync, readFileSync, statSync} from 'node:fs';
import {join, resolve} from 'node:path';
import de from '../../../../public/i18n/de.json';
import en from '../../../../public/i18n/en.json';

/** Every leaf key with its dotted path, so a group added to one catalog only shows up by name. */
function keys(catalog: unknown, prefix = ''): string[] {
    if (typeof catalog !== 'object' || catalog === null) {
        return [prefix.slice(0, -1)];
    }
    return Object.entries(catalog).flatMap(([key, value]) => keys(value, `${prefix}${key}.`));
}

const APP = resolve(process.cwd(), 'src/app');

/** Every `.ts` and `.html` under `src/app` that ships, which leaves the specs out: a key only a spec names is dead. */
function sourcesUnder(dir: string): string[] {
    return readdirSync(dir).flatMap((entry) => {
        const path = join(dir, entry);
        if (statSync(path).isDirectory()) return sourcesUnder(path);
        return /\.(ts|html)$/.test(entry) && !entry.endsWith('.spec.ts') ? [path] : [];
    });
}

/**
 * The prefixes a key is composed under at runtime: a template literal such as
 * `detail.kind.${kind}`, or a concatenation such as `'help.chapter.' + id` in a template —
 * a dotted path ending in a dot, followed by the part that is not known until then.
 */
function dynamicPrefixes(source: string): Set<string> {
    const composed = [/`([\w-]+(?:\.[\w-]+)*)\.\$\{/g, /['"]([\w-]+(?:\.[\w-]+)*)\.['"]\s*\+/g];
    return new Set(composed.flatMap((pattern) => [...source.matchAll(pattern)].map((match) => match[1])));
}

/** Source without its line, block and HTML comments, so a key named in a note does not count. */
function withoutComments(source: string): string {
    // The HTML pass repeats until nothing changes: one pass over `<!<!-- a -->-- b -->` leaves
    // a comment standing, and a key inside it would then count as a reference.
    let text = source.replace(/\/\*[\s\S]*?\*\//g, '');
    let previous: string;
    do {
        previous = text;
        text = text.replace(/<!--[\s\S]*?-->/g, '');
    } while (text !== previous);
    return text.replace(/(^|[^:'"`])\/\/[^\n]*/g, '$1');
}

/** Escapes regex metacharacters so a key is matched literally inside a RegExp source. */
function escapeRegExp(value: string): string {
    return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * The two catalogs carry the same keys. Transloco falls back to English for a key German
 * lacks, so the defect this catches is invisible on screen: a sentence in the wrong
 * language, on the one screen nobody opened in German. Measured on 2026-09-23 the catalogs
 * were already equal at 464 keys, which is why this covers the whole catalog rather than
 * the group that was added that day.
 */
describe('i18n catalogs', () => {
    it('hold the same keys in English and German', () => {
        const english = keys(en).sort();
        const german = keys(de).sort();

        expect(english.filter((key) => !german.includes(key))).toEqual([]);
        expect(german.filter((key) => !english.includes(key))).toEqual([]);
    });

    it('name a count as an ICU plural wherever a toast carries one', () => {
        // A `count` key without a plural is "1 offers" on the one day a run finds exactly one.
        for (const catalog of [en.toast, de.toast]) {
            for (const [key, text] of Object.entries(catalog)) {
                if (key.startsWith('count')) {
                    expect(text, key).toContain('plural');
                }
            }
        }
    });

    it('hold no key that no source file references', () => {
        // A key is referenced when it appears whole in a source, or when its parent is a
        // prefix the code composes a key under. Never an allowlist by name: a key built in a
        // way this misses is a reason to widen the pattern, not to exempt the key.
        // Comments are stripped first: a key spelled in a note about a parked screen is not a
        // reference. And the key must stand whole — `shortlist.portal` inside
        // `shortlist.portalsLabel` is another key, not this one.
        const source = sourcesUnder(APP)
            .map((file) => withoutComments(readFileSync(file, 'utf8')))
            .join('\n');
        const prefixes = dynamicPrefixes(source);
        const referenced = (key: string) =>
            new RegExp(`(^|[^\\w.])${escapeRegExp(key)}(?![\\w.])`, 'm').test(source);
        const unused = keys(en).filter(
            (key) => !referenced(key) && !prefixes.has(key.slice(0, key.lastIndexOf('.'))),
        );

        expect(unused, `catalog keys no source file references: ${unused.join(', ')}`).toEqual([]);
    });
});
