import {readdirSync, readFileSync, statSync} from 'node:fs';
import {join, resolve} from 'node:path';
import {FALLBACK} from './chart-theme';
import {deltaEOk, hueDistance, oklabToSrgb, parseHex, parseOklch, polar, toHex} from './color-math';
import {ResolvedTheme, THEME_SURFACE_HEX} from './theme.model';

/**
 * The colour guard (house rule DS-APP-05): the palette is pinned, and every token a
 * component reads exists.
 *
 * <p>It reads the stylesheets from disk rather than asking a browser, because the
 * questions it asks are about the files: does every `oklch()` have a truthful hex twin,
 * does the chart fallback still mirror the theme, does every `var(--x)` under `src/app`
 * name something that is defined. An undefined custom property resolves to nothing and
 * the element renders transparent with no error anywhere, which is the failure this spec
 * turns into a red line.
 */

const FRONTEND = process.cwd();
const STYLES = resolve(FRONTEND, 'src/styles.css');
const TOKEN_FILES = ['src/styles/tokens.css', 'src/styles/motion.css', 'src/styles/primitives.css'];
const APP = resolve(FRONTEND, 'src/app');

/**
 * Names Tailwind and DaisyUI define at build time, which the stylesheet never spells out
 * as a declaration. A reference to one of these is defined by the toolchain.
 */
const TOOLCHAIN_PREFIXES = ['--color-', '--radius-', '--size-', '--font-', '--text-', '--tw-', '--spacing'];
const TOOLCHAIN_NAMES = ['--border', '--depth', '--noise'];

/** Written on the element at runtime by `shared/popover/anchor-for.ts`, never declared in CSS. */
const RUNTIME_ALLOWLIST = ['--lg-anchor-x', '--lg-anchor-y'];

/** The seven navigation destinations, in the order the nav lists them. */
const SECTIONS = ['dashboard', 'shortlist', 'pipeline', 'analytics', 'sources', 'review', 'rules'] as const;

interface ThemeBlock {
    name: string;
    /** every custom property in the block, as written */
    values: Map<string, string>;
    /** the hex a comment beside the value claims, where there is one */
    hexTwins: Map<string, string>;
}

function readThemeBlocks(css: string): ThemeBlock[] {
    const blocks: ThemeBlock[] = [];
    const re = /@plugin\s+"daisyui\/theme"\s*\{([\s\S]*?)\n\}/g;
    for (const match of css.matchAll(re)) {
        const body = match[1];
        const name = /name:\s*'([^']+)'/.exec(body)?.[1] ?? '(unnamed)';
        const values = new Map<string, string>();
        const hexTwins = new Map<string, string>();
        for (const line of body.split('\n')) {
            const decl = /^\s*(--[\w-]+):\s*([^;]+);(?:\s*\/\*\s*(#[0-9A-Fa-f]{6})\b[^*]*\*\/)?/.exec(line);
            if (!decl) continue;
            values.set(decl[1], decl[2].trim());
            if (decl[3]) hexTwins.set(decl[1], decl[3].toUpperCase());
        }
        blocks.push({name, values, hexTwins});
    }
    return blocks;
}

/** The three `--lg-*` corrective blocks: light, dark, and the system-dark media copy. */
function readCorrectiveBlocks(css: string): Map<string, Map<string, string>> {
    const out = new Map<string, Map<string, string>>();
    const take = (label: string, selectorRe: RegExp) => {
        const m = selectorRe.exec(css);
        if (!m) throw new Error(`corrective block not found: ${label}`);
        const decls = new Map<string, string>();
        for (const d of m[1].matchAll(/(--lg-[\w-]+):\s*([^;]+);/g)) decls.set(d[1], d[2].trim());
        out.set(label, decls);
    };
    take('light', /\[data-theme='lg-light'\],\s*:where\(:root\)\s*\{([\s\S]*?)\n\}/);
    take('dark', /\n\[data-theme='lg-dark'\]\s*\{([\s\S]*?)\n\}/);
    take('system-dark', /:root:not\(\[data-theme\]\)\s*\{([\s\S]*?)\n\s*\}/);
    return out;
}

function cssFilesUnder(dir: string): string[] {
    const found: string[] = [];
    for (const entry of readdirSync(dir)) {
        const path = join(dir, entry);
        if (statSync(path).isDirectory()) found.push(...cssFilesUnder(path));
        else if (entry.endsWith('.css')) found.push(path);
    }
    return found;
}

function definedNames(): Set<string> {
    const names = new Set<string>();
    const sources = [STYLES, ...TOKEN_FILES.map(f => resolve(FRONTEND, f))].filter(f => {
        try {
            return statSync(f).isFile();
        } catch {
            return false;
        }
    });
    for (const file of sources) {
        for (const m of readFileSync(file, 'utf8').matchAll(/(^|[\s{;])(--[\w-]+)\s*:/g)) names.add(m[2]);
    }
    return names;
}

function isToolchainName(name: string): boolean {
    return TOOLCHAIN_NAMES.includes(name) || TOOLCHAIN_PREFIXES.some(p => name.startsWith(p));
}

describe('the palette (ISC-222)', () => {
    const css = readFileSync(STYLES, 'utf8');
    const themes = readThemeBlocks(css);

    it('declares exactly the two themes', () => {
        expect(themes.map(t => t.name)).toEqual(['lg-light', 'lg-dark']);
    });

    it.each(themes.map(t => [t.name, t] as const))('%s: every colour is oklch() and inside the sRGB gamut', (_name, theme) => {
        const colours = [...theme.values].filter(([k]) => k.startsWith('--color-'));
        expect(colours.length).toBeGreaterThan(10);
        for (const [key, value] of colours) {
            expect(value, key).toMatch(/^oklch\(/);
            expect(oklabToSrgb(parseOklch(value)).inGamut, `${key}: ${value} leaves sRGB`).toBe(true);
        }
    });

    it.each(themes.map(t => [t.name, t] as const))('%s: every hex comment is the value it stands beside, within one step per channel', (_name, theme) => {
        expect(theme.hexTwins.size).toBeGreaterThan(5);
        for (const [key, hex] of theme.hexTwins) {
            const actual = oklabToSrgb(parseOklch(theme.values.get(key)!));
            const claimed = parseHex(hex);
            for (const channel of ['r', 'g', 'b'] as const) {
                expect(Math.abs(actual[channel] - claimed[channel]), `${key}: ${hex} vs ${toHex(actual)}`).toBeLessThanOrEqual(1);
            }
        }
    });

    it('the chart fallback is the light theme, value for value', () => {
        const light = themes.find(t => t.name === 'lg-light')!;
        const corrective = readCorrectiveBlocks(css).get('light')!;
        const hexOf = (value: string) => toHex(oklabToSrgb(parseOklch(value)));
        expect(FALLBACK.primary).toBe(hexOf(light.values.get('--color-primary')!));
        expect(FALLBACK.secondary).toBe(hexOf(light.values.get('--color-secondary')!));
        expect(FALLBACK.accent).toBe(hexOf(light.values.get('--color-accent')!));
        expect(FALLBACK.track).toBe(hexOf(light.values.get('--color-base-300')!));
        expect(FALLBACK.surface).toBe(hexOf(light.values.get('--color-base-100')!));
        expect(FALLBACK.ink).toBe(hexOf(light.values.get('--color-base-content')!));
        expect(FALLBACK.label).toBe(hexOf(corrective.get('--lg-muted')!));
        expect(FALLBACK.stages).toEqual(
            Array.from({length: 7}, (_, i) => hexOf(corrective.get(`--lg-chart-stage-${i + 1}`)!)),
        );
    });

    it('the surface constants are each theme\'s --color-base-100, value for value (ISC-327)', () => {
        const hexOf = (value: string) => toHex(oklabToSrgb(parseOklch(value)));
        for (const theme of themes) {
            const name = theme.name as ResolvedTheme;
            expect(THEME_SURFACE_HEX[name], name).toBe(hexOf(theme.values.get('--color-base-100')!));
        }
    });
});

describe('the tokens (ISC-223)', () => {
    const css = readFileSync(STYLES, 'utf8');

    it('the dark and the system-dark corrective blocks define the same names with the same values', () => {
        const blocks = readCorrectiveBlocks(css);
        const dark = blocks.get('dark')!;
        const system = blocks.get('system-dark')!;
        expect([...system.keys()].sort()).toEqual([...dark.keys()].sort());
        for (const [name, value] of dark) expect(system.get(name), name).toBe(value);
    });

    it('the light corrective block defines every name the dark one does', () => {
        const blocks = readCorrectiveBlocks(css);
        expect([...blocks.get('light')!.keys()].sort()).toEqual([...blocks.get('dark')!.keys()].sort());
    });

    it('every var(--x) a component reads is defined, and a fallback does not count', () => {
        const defined = definedNames();
        const missing: string[] = [];
        for (const file of cssFilesUnder(APP)) {
            const text = readFileSync(file, 'utf8');
            for (const m of text.matchAll(/var\(\s*(--[\w-]+)/g)) {
                const name = m[1];
                if (defined.has(name) || isToolchainName(name) || RUNTIME_ALLOWLIST.includes(name)) continue;
                missing.push(`${file.slice(FRONTEND.length + 1)}: ${name}`);
            }
        }
        expect(missing).toEqual([]);
    });
});

describe('the corrective blocks (ISC-222)', () => {
    const css = readFileSync(STYLES, 'utf8');

    it.each(['light', 'dark'])('%s: every --lg-* hex comment is the value it stands beside', label => {
        const block = correctiveBlockSource(css, label);
        let checked = 0;
        for (const line of block.split('\n')) {
            const decl = /^\s*(--lg-[\w-]+):\s*(oklch\([^)]*\));\s*\/\*\s*(#[0-9A-Fa-f]{6})\b/.exec(line);
            if (!decl) continue;
            const actual = oklabToSrgb(parseOklch(decl[2]));
            expect(actual.inGamut, `${decl[1]} leaves sRGB`).toBe(true);
            const claimed = parseHex(decl[3]);
            for (const channel of ['r', 'g', 'b'] as const) {
                expect(Math.abs(actual[channel] - claimed[channel]), `${decl[1]}: ${decl[3]} vs ${toHex(actual)}`).toBeLessThanOrEqual(1);
            }
            checked++;
        }
        expect(checked).toBeGreaterThan(10);
    });
});

/**
 * ISC-225: the seven section colours are one family (same L, same C, only the hue turns)
 * and none of them can be mistaken for the signal, the primary or a semantic colour.
 */
describe('the section colours (ISC-225)', () => {
    const css = readFileSync(STYLES, 'utf8');
    const themes = readThemeBlocks(css);
    const corrective = readCorrectiveBlocks(css);

    it.each([
        ['lg-light', 'light'],
        ['lg-dark', 'dark'],
    ])('%s: seven sections, one lightness, one chroma, hues at least 30° apart', (theme, label) => {
        const block = corrective.get(label)!;
        const sections = SECTIONS.map(s => ({s, lab: parseOklch(block.get(`--lg-section-${s}`)!)}));
        expect(sections).toHaveLength(7);
        const ls = sections.map(x => x.lab.l);
        const cs = sections.map(x => polar(x.lab).c);
        expect(Math.max(...ls) - Math.min(...ls), 'lightness band').toBeLessThanOrEqual(0.005);
        expect(Math.max(...cs) - Math.min(...cs), 'chroma band').toBeLessThanOrEqual(0.005);
        for (let i = 0; i < sections.length; i++) {
            for (let j = i + 1; j < sections.length; j++) {
                const d = hueDistance(polar(sections[i].lab).h, polar(sections[j].lab).h);
                expect(d, `${sections[i].s} vs ${sections[j].s}`).toBeGreaterThanOrEqual(30);
            }
        }
        expect(theme).toBeTruthy();
    });

    it.each([
        ['lg-light', 'light'],
        ['lg-dark', 'dark'],
    ])('%s: every section is clear of the signal (ΔE ≥ 0.10, hue ≥ 25°) and of primary and the semantic four (ΔE ≥ 0.06)', (theme, label) => {
        const values = themes.find(t => t.name === theme)!.values;
        const block = corrective.get(label)!;
        const others = [
            ['accent', 0.1],
            ['primary', 0.06],
            ['info', 0.06],
            ['success', 0.06],
            ['warning', 0.06],
            ['error', 0.06],
        ] as const;
        for (const s of SECTIONS) {
            const lab = parseOklch(block.get(`--lg-section-${s}`)!);
            for (const [name, floor] of others) {
                const d = deltaEOk(lab, parseOklch(values.get(`--color-${name}`)!));
                expect(d, `${s} vs ${name}`).toBeGreaterThanOrEqual(floor);
                if (name === 'accent') {
                    // ΔE alone lets a section sit on the signal's hue at a lower chroma, which
                    // reads as "the signal, faded"; the hue floor is what keeps them apart.
                    const hue = hueDistance(polar(lab).h, polar(parseOklch(values.get('--color-accent')!)).h);
                    expect(hue, `${s} vs the signal, hue`).toBeGreaterThanOrEqual(25);
                }
            }
        }
    });
});

/** The raw text of one corrective block, for the line-level checks that want the comments. */
function correctiveBlockSource(css: string, label: string): string {
    const re =
        label === 'light'
            ? /\[data-theme='lg-light'\],\s*:where\(:root\)\s*\{([\s\S]*?)\n\}/
            : /\n\[data-theme='lg-dark'\]\s*\{([\s\S]*?)\n\}/;
    const m = re.exec(css);
    if (!m) throw new Error(`corrective block not found: ${label}`);
    return m[1];
}
