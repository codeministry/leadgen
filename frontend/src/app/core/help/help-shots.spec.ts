import {existsSync, readdirSync, readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {HELP_SHOTS} from './help-chapters';

/**
 * Every screenshot the help names exists four times, and nothing else sits beside them.
 *
 * <p>The drawer builds the file name from the reader's language and theme, so a missing
 * variant is a broken image in exactly one of four combinations, the one nobody checked.
 * And a file with no entry is weight every installed app caches for nothing.
 */

const SHOTS = resolve(process.cwd(), 'public/help/shots');
const LANGUAGES = ['en', 'de'] as const;
const THEMES = ['light', 'dark'] as const;

/** Well above what a drawer-wide WebP needs; a PNG saved under the wrong name trips it. */
const MAX_BYTES = 160 * 1024;

function files(language: string): string[] {
    const dir = resolve(SHOTS, language);
    return existsSync(dir) ? readdirSync(dir).sort() : [];
}

/** Width and height from a lossy or lossless WebP header, which is all `help:shots` writes. */
function webpSize(bytes: Buffer): {width: number; height: number} | null {
    if (bytes.toString('ascii', 0, 4) !== 'RIFF' || bytes.toString('ascii', 8, 12) !== 'WEBP') return null;
    const chunk = bytes.toString('ascii', 12, 16);
    if (chunk === 'VP8 ') {
        return {width: bytes.readUInt16LE(26) & 0x3fff, height: bytes.readUInt16LE(28) & 0x3fff};
    }
    if (chunk === 'VP8L') {
        const bits = bytes.readUInt32LE(21);
        return {width: (bits & 0x3fff) + 1, height: ((bits >> 14) & 0x3fff) + 1};
    }
    if (chunk === 'VP8X') {
        return {width: bytes.readUIntLE(24, 3) + 1, height: bytes.readUIntLE(27, 3) + 1};
    }
    return null;
}

describe('help screenshots', () => {
    const ids = Object.keys(HELP_SHOTS);

    it('exist for every language and theme, and nothing else is in the folder', () => {
        const expected = ids.flatMap((id) => THEMES.map((theme) => `${id}-${theme}.webp`)).sort();
        for (const language of LANGUAGES) {
            expect(files(language), language).toEqual(expected);
        }
    });

    it('are WebP at the size the drawer reserves for them, and small', () => {
        for (const language of LANGUAGES) {
            for (const name of files(language)) {
                const bytes = readFileSync(resolve(SHOTS, language, name));
                const id = name.replace(/-(?:light|dark)\.webp$/, '') as keyof typeof HELP_SHOTS;
                expect(webpSize(bytes), `${language}/${name}`).toEqual(HELP_SHOTS[id]);
                expect(bytes.length, `${language}/${name}`).toBeLessThanOrEqual(MAX_BYTES);
            }
        }
    });
});
