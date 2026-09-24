import {createHash} from 'node:crypto';
import {existsSync, readdirSync, readFileSync} from 'node:fs';
import {resolve} from 'node:path';

const FRONTEND = process.cwd();
const SOURCES = resolve(FRONTEND, 'src/help/diagrams');
const RENDERED = resolve(FRONTEND, 'public/help/diagrams');
const BUILD_SCRIPT = resolve(FRONTEND, 'tools/build-help-diagrams.ts');
const STAMP = /^<!-- source-sha256: ([0-9a-f]{64}) -->/;

/** A colour written out rather than read from a token: hex, or any of the CSS colour functions. */
const COLOUR_LITERAL = /#[0-9a-f]{3,8}\b|\b(?:rgba?|hsla?|oklch|oklab|lab|lch|hwb)\(/gi;

/** Every diagram id, taken from the sources so a new `.mmd` is covered without a list to edit. */
function diagramIds(): string[] {
    return readdirSync(SOURCES)
        .filter((name) => name.endsWith('.mmd'))
        .map((name) => name.slice(0, -'.mmd'.length))
        .sort();
}

/**
 * The source and the build script together: the script carries the map from Mermaid's colours
 * to the app's tokens, so a changed token map marks every SVG stale just like a changed diagram.
 * Must stay in step with `stampOf` in `tools/build-help-diagrams.ts`.
 */
function expectedStamp(id: string): string {
    return createHash('sha256')
        .update(readFileSync(resolve(SOURCES, `${id}.mmd`)))
        .update(readFileSync(BUILD_SCRIPT))
        .digest('hex');
}

function rendered(id: string): string {
    return readFileSync(resolve(RENDERED, `${id}.svg`), 'utf8');
}

/**
 * The drawer inlines the committed SVGs and never renders Mermaid in the browser, so a source
 * edited without `bun run help:diagrams` would ship a picture of the old diagram with nothing
 * on screen to say so. Each SVG carries the sha256 of what it was rendered from; this spec
 * compares that stamp with the source and the script as they are now and names the stale file.
 *
 * <p>The colours are the app's CSS variables, so one file serves both themes; a colour literal
 * left in the file would be the same in both, which is what this spec refuses.
 */
describe('help diagrams (ISC-314)', () => {
    it('have at least the three the how-it-works chapter needs', () => {
        expect(diagramIds()).toEqual(expect.arrayContaining(['application-states', 'parts', 'run-phases']));
    });

    it('are one SVG per source and nothing else — no per-theme copies', () => {
        const files = readdirSync(RENDERED).sort();
        expect(files).toEqual(diagramIds().map((id) => `${id}.svg`));
    });

    for (const id of diagramIds()) {
        describe(`${id}.svg`, () => {
            it('exists and is rendered from the current source and token map', () => {
                const file = resolve(RENDERED, `${id}.svg`);
                expect(existsSync(file), `${id}.svg is missing — run bun run help:diagrams`).toBe(true);

                const stamp = STAMP.exec(rendered(id))?.[1];
                expect(stamp, `${id}.svg is stale — run bun run help:diagrams`).toBe(expectedStamp(id));
            });

            it('has no colour literal left, only var(--…) tokens', () => {
                const svg = rendered(id);
                expect(svg.match(COLOUR_LITERAL) ?? []).toEqual([]);
                expect(svg).toContain('var(--color-base-content)');
            });

            it('sets no font of its own, so the text takes the app font', () => {
                expect(rendered(id)).not.toMatch(/font-family/i);
            });

            it('is plain SVG text: no foreignObject, no XML prolog, a unique id', () => {
                const svg = rendered(id);
                expect(svg).not.toContain('<foreignObject');
                expect(svg).not.toContain('<?xml');
                expect(svg).toContain(`id="help-diagram-${id}"`);
            });
        });
    }
});
