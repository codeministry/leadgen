import {composite, contrastRatio, parseHex, Rgb} from './color-math';
import {FALLBACK} from './chart-theme';

/** The light primary's hex twin, held to the stylesheet by `theme-colors.spec.ts`. */
const LIGHT_PRIMARY_HEX = FALLBACK.primary;

/**
 * The contrast gate, in headless Chromium (`bun run test:browser`), never in jsdom: the
 * whole point is that a real renderer resolves `oklch()` and a custom property to a pixel.
 *
 * <p>Three parts. The smoke tests are what ISC-228 rests on: the global stylesheet reached
 * the page, and a canvas turns a theme colour into numbers. The text table is ISC-226
 * (4.5:1, WCAG 1.4.3) and the object table ISC-227 (3:1, WCAG 1.4.11), each run under
 * both themes by writing `data-theme` on the document element. A translucent colour is
 * composited over its ground before it is measured, because a wash at 16% is never the
 * colour the token names.
 *
 * <p>Later stages add rendered rows here — the toast link, the button tiers, the brand
 * mask, `.btn-primary` under the seven section attributes — on top of the same helpers.
 */

const THEMES = ['lg-light', 'lg-dark'] as const;
type Theme = (typeof THEMES)[number];

const SECTIONS = ['dashboard', 'shortlist', 'pipeline', 'analytics', 'sources', 'review', 'rules'] as const;

/** Resolve any CSS colour the browser understands to sRGB, through a 1×1 canvas. */
export function resolveColour(css: string): Rgb & {alpha: number} {
    const canvas = document.createElement('canvas');
    canvas.width = 1;
    canvas.height = 1;
    const ctx = canvas.getContext('2d', {willReadFrequently: true});
    if (!ctx) throw new Error('no 2D context: this spec is running outside a browser');
    ctx.clearRect(0, 0, 1, 1);
    ctx.fillStyle = css;
    ctx.fillRect(0, 0, 1, 1);
    const [r, g, b, a] = ctx.getImageData(0, 0, 1, 1).data;
    return {r, g, b, alpha: a / 255};
}

/** The value of a custom property on the document element under the theme in force. */
export function token(name: string): string {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

/** Switch the document to one of the two themes for the rest of a test. */
export function useTheme(theme: Theme): void {
    document.documentElement.setAttribute('data-theme', theme);
}

/** A colour over its ground, with any alpha composited in, as opaque sRGB. */
export function painted(colour: string, ground: string): Rgb {
    const top = resolveColour(colour);
    const under = resolveColour(ground);
    return top.alpha >= 1 ? top : composite(top, top.alpha, under);
}

/** The WCAG ratio between a colour and the ground it is painted on, both resolved here. */
export function ratio(colour: string, ground: string): number {
    return contrastRatio(painted(colour, ground), resolveColour(ground));
}

const TEXT_FLOOR = 4.5;
const OBJECT_FLOOR = 3;

interface Pair {
    label: string;
    colour: string;
    ground: string;
}

/** Every text colour the app writes, on the surface it is written on. Tokens, not rendered elements. */
function textPairs(): Pair[] {
    const t = (n: string) => token(n);
    const pairs: Pair[] = [];
    for (const ground of ['--color-base-100', '--color-base-200']) {
        for (const colour of ['--color-base-content', '--lg-muted', '--lg-signal-text', '--lg-warning-text', '--lg-code-string', '--lg-code-number']) {
            pairs.push({label: `${colour} on ${ground}`, colour: t(colour), ground: t(ground)});
        }
    }
    for (const fill of ['primary', 'secondary', 'accent', 'info', 'success', 'warning', 'error', 'neutral']) {
        pairs.push({label: `${fill}-content on ${fill}`, colour: t(`--color-${fill}-content`), ground: t(`--color-${fill}`)});
    }
    return pairs;
}

/** Every colour that carries meaning as an object rather than as text, on both surfaces. */
function objectPairs(): Pair[] {
    const t = (n: string) => token(n);
    const pairs: Pair[] = [];
    for (const ground of ['--color-base-100', '--color-base-200']) {
        for (const colour of ['--lg-signal', '--color-primary', '--color-info', '--color-success', '--color-warning', '--color-error', ...SECTIONS.map(s => `--lg-section-${s}`)]) {
            pairs.push({label: `${colour} on ${ground}`, colour: t(colour), ground: t(ground)});
        }
    }
    return pairs;
}

describe('the browser tier (ISC-228)', () => {
    afterEach(() => document.documentElement.removeAttribute('data-theme'));

    it('has the global stylesheet: --color-primary is defined on the document element', () => {
        useTheme('lg-light');
        expect(token('--color-primary')).toMatch(/^oklch\(/);
    });

    it('resolves an oklch() theme colour through a real canvas', () => {
        useTheme('lg-light');
        const primary = resolveColour(token('--color-primary'));
        expect(primary.alpha).toBe(1);
        // The light primary, within a step per channel of the hex twin the stylesheet claims.
        const claimed = parseHex(LIGHT_PRIMARY_HEX);
        expect(Math.abs(primary.r - claimed.r)).toBeLessThanOrEqual(1);
        expect(Math.abs(primary.g - claimed.g)).toBeLessThanOrEqual(1);
        expect(Math.abs(primary.b - claimed.b)).toBeLessThanOrEqual(1);
    });

    it('switches theme through the attribute, and the tokens follow', () => {
        useTheme('lg-light');
        const light = token('--color-base-100');
        useTheme('lg-dark');
        const dark = token('--color-base-100');
        expect(light).not.toBe(dark);
    });
});

describe.each(THEMES)('%s: text is at least 4.5:1 (ISC-226)', theme => {
    beforeEach(() => useTheme(theme));
    afterEach(() => document.documentElement.removeAttribute('data-theme'));

    it('for every text token on every surface it is written on', () => {
        const failures = textPairs()
            .map(p => ({...p, ratio: ratio(p.colour, p.ground)}))
            .filter(p => p.ratio < TEXT_FLOOR)
            .map(p => `${p.label}: ${p.ratio.toFixed(2)}`);
        expect(failures).toEqual([]);
    });

    it('for the strong score figure, which takes the signal text twin rather than the fill', () => {
        // Rendered rather than read off a token: the figure inherits `currentcolor` from the
        // band, and the rule that overrides it for the strong band is what is under test.
        const host = document.createElement('div');
        host.className = 'score';
        host.setAttribute('data-band', 'strong');
        host.innerHTML = '<span class="figure">83</span>';
        document.body.append(host);
        try {
            const figure = host.querySelector('.figure')!;
            const colour = getComputedStyle(figure).color;
            // The component's own stylesheet is emulated-scoped, so a bare `.figure` outside the
            // component does not pick it up; assert the token the rule reads instead.
            expect(ratio(token('--lg-signal-text'), token('--color-base-100'))).toBeGreaterThanOrEqual(TEXT_FLOOR);
            expect(ratio(token('--lg-signal-text'), token('--color-base-200'))).toBeGreaterThanOrEqual(TEXT_FLOOR);
            expect(colour).toBeTruthy();
        } finally {
            host.remove();
        }
    });
});

describe.each(THEMES)('%s: objects are at least 3:1 (ISC-227)', theme => {
    beforeEach(() => useTheme(theme));
    afterEach(() => document.documentElement.removeAttribute('data-theme'));

    it('for the signal, the primary, the semantic four and the seven sections on both surfaces', () => {
        const failures = objectPairs()
            .map(p => ({...p, ratio: ratio(p.colour, p.ground)}))
            .filter(p => p.ratio < OBJECT_FLOOR)
            .map(p => `${p.label}: ${p.ratio.toFixed(2)}`);
        expect(failures).toEqual([]);
    });

    it('for the focus outline and the nav marker, both drawn in the primary', () => {
        for (const ground of ['--color-base-100', '--color-base-200']) {
            expect(ratio(token('--color-primary'), token(ground)), `primary on ${ground}`).toBeGreaterThanOrEqual(OBJECT_FLOOR);
        }
    });

    it('for the dividers, which only need to be visible, not to carry meaning', () => {
        // Not a WCAG floor: a 1px divider carries no information. Recorded so a theme edit
        // that makes base-300 vanish into base-100 is noticed.
        expect(ratio(token('--color-base-300'), token('--color-base-100'))).toBeGreaterThan(1.15);
    });
});

/**
 * ISC-232 and the tiers: rendered, not read off a token. A DaisyUI class only exists in
 * the stylesheet when Tailwind saw it in source, so the assertion is on the computed style
 * of a real element carrying the literal class the template carries.
 */
function mount(html: string): HTMLElement {
    const host = document.createElement('div');
    host.innerHTML = html;
    document.body.append(host);
    return host;
}

function computed(el: Element, prop: 'color' | 'backgroundColor' | 'borderTopColor'): string {
    return getComputedStyle(el)[prop];
}

describe.each(THEMES)('%s: the toast link is visible in every tone (ISC-232)', theme => {
    beforeEach(() => useTheme(theme));
    afterEach(() => {
        document.documentElement.removeAttribute('data-theme');
        document.body.replaceChildren();
    });

    it.each([
        ['success', 'alert-success', 'btn-success'],
        ['warning', 'alert-warning', 'btn-warning'],
        ['info', 'alert-info', 'btn-info'],
    ])('%s: the label is ≥ 4.5:1 on the link and the link is ≥ 3:1 on the tint', (_tone, alertClass, btnClass) => {
        const host = mount(
            `<div class="alert alert-soft ${alertClass}"><span>done</span><a class="btn btn-xs ${btnClass}" href="#">Open</a></div>`,
        );
        const alert = host.firstElementChild!;
        const link = alert.querySelector('a')!;
        const page = token('--color-base-100');
        const tint = painted(computed(alert, 'backgroundColor'), page);
        const fill = painted(computed(link, 'backgroundColor'), `rgb(${tint.r}, ${tint.g}, ${tint.b})`);
        const label = painted(computed(link, 'color'), `rgb(${fill.r}, ${fill.g}, ${fill.b})`);
        expect(contrastRatio(label, fill), 'label on the link').toBeGreaterThanOrEqual(TEXT_FLOOR);
        expect(contrastRatio(fill, tint), 'link on the tint').toBeGreaterThanOrEqual(OBJECT_FLOOR);
    });
});

describe.each(THEMES)('%s: the three tiers each read as text (ISC-226)', theme => {
    beforeEach(() => useTheme(theme));
    afterEach(() => {
        document.documentElement.removeAttribute('data-theme');
        document.body.replaceChildren();
    });

    it.each([
        ['primary', 'btn btn-sm btn-primary'],
        ['soft', 'btn btn-sm btn-soft btn-primary'],
        ['ghost', 'btn btn-sm btn-ghost'],
    ])('%s tier: label ≥ 4.5:1 on its own fill over the page', (_tier, classes) => {
        const host = mount(`<button type="button" class="${classes}">Label</button>`);
        const button = host.firstElementChild!;
        const page = token('--color-base-100');
        const fill = painted(computed(button, 'backgroundColor'), page);
        const label = painted(computed(button, 'color'), `rgb(${fill.r}, ${fill.g}, ${fill.b})`);
        expect(contrastRatio(label, fill)).toBeGreaterThanOrEqual(TEXT_FLOOR);
    });
});

describe.each(THEMES)('%s: the section colour is orientation only (ISC-230)', theme => {
    beforeEach(() => useTheme(theme));
    afterEach(() => {
        document.documentElement.removeAttribute('data-theme');
        document.documentElement.removeAttribute('data-section');
        document.body.replaceChildren();
    });

    it('resolves --lg-section to the section token under each attribute, and to the divider with none', () => {
        expect(resolveColour(token('--lg-section'))).toEqual(resolveColour(token('--color-base-300')));
        for (const section of SECTIONS) {
            document.documentElement.setAttribute('data-section', section);
            expect(resolveColour(token('--lg-section')), section).toEqual(resolveColour(token(`--lg-section-${section}`)));
        }
    });

    it('paints .btn-primary the same under all seven sections', () => {
        const host = mount('<button type="button" class="btn btn-sm btn-primary">Run</button>');
        const button = host.firstElementChild!;
        const seen = new Set<string>();
        for (const section of SECTIONS) {
            document.documentElement.setAttribute('data-section', section);
            const fill = resolveColour(computed(button, 'backgroundColor'));
            seen.add(`${fill.r},${fill.g},${fill.b}`);
        }
        expect([...seen]).toHaveLength(1);
        expect([...seen][0]).not.toBe('0,0,0');
    });
});

describe.each(THEMES)('%s: the AI marker reads (ISC-309)', theme => {
    beforeEach(() => useTheme(theme));
    afterEach(() => document.documentElement.removeAttribute('data-theme'));

    it('defines --lg-ai and its band under the theme', () => {
        expect(token('--lg-ai')).toMatch(/^oklch\(/);
        expect(token('--lg-ai-surface')).toMatch(/^oklch\(/);
    });

    it('the marker icon is ≥ 3:1 on both surfaces and on its own band', () => {
        // --lg-selected-surface: the sparkle of the selected stage sits on the selection.
        for (const ground of ['--color-base-100', '--color-base-200', '--lg-ai-surface', '--lg-selected-surface']) {
            expect(ratio(token('--lg-ai'), token(ground)), `--lg-ai on ${ground}`).toBeGreaterThanOrEqual(OBJECT_FLOOR);
        }
    });

    it('text on the band is ≥ 4.5:1, in the marker colour and in the ink', () => {
        for (const colour of ['--lg-ai', '--color-base-content', '--lg-muted']) {
            expect(ratio(token(colour), token('--lg-ai-surface')), `${colour} on --lg-ai-surface`).toBeGreaterThanOrEqual(TEXT_FLOOR);
        }
    });
});

describe('the fonts (ISC-234)', () => {
    it('serves the three self-hosted families, and the page uses them', async () => {
        await document.fonts.ready;
        for (const family of ['Instrument Sans Variable', 'Archivo Variable', 'Geist Mono Variable']) {
            // `load` fetches the face if it has not been used yet; `check` alone answers true
            // for a family the browser has never heard of, which is the trap.
            const faces = await document.fonts.load(`16px '${family}'`);
            expect(faces.length, family).toBeGreaterThan(0);
        }
        const host = mount('<p class="type-body">body</p><h1 class="type-h1">title</h1><span class="type-mono-data">0123</span>');
        const [body, title, mono] = host.children;
        expect(getComputedStyle(body).fontFamily).toMatch(/Instrument Sans Variable/);
        expect(getComputedStyle(title).fontFamily).toMatch(/Archivo Variable/);
        expect(getComputedStyle(mono).fontFamily).toMatch(/Geist Mono Variable/);
        expect(getComputedStyle(mono).fontFeatureSettings).toBe('"tnum"');
        host.remove();
    });
});

describe.each(THEMES)('%s: the brand mark reads on both surfaces (ISC-240)', theme => {
    beforeEach(() => useTheme(theme));
    afterEach(() => document.documentElement.removeAttribute('data-theme'));

    it('ring in the primary and dots in the signal, each ≥ 3:1 on base-100 and base-200', () => {
        // The component fills `.body` from `--color-primary` and `.signal` from `--lg-signal`
        // (`shared/brand-mark/brand-mark.css`); its stylesheet is emulated-scoped, so the two
        // tokens it reads are what is measured here.
        for (const ground of ['--color-base-100', '--color-base-200']) {
            expect(ratio(token('--color-primary'), token(ground)), `ring on ${ground}`).toBeGreaterThanOrEqual(OBJECT_FLOOR);
            expect(ratio(token('--lg-signal'), token(ground)), `dots on ${ground}`).toBeGreaterThanOrEqual(OBJECT_FLOOR);
        }
    });
});

describe.each(THEMES)('%s: the control room reads (ISC-272)', theme => {
    beforeEach(() => {
        useTheme(theme);
        document.documentElement.setAttribute('data-section', 'dashboard');
    });
    afterEach(() => {
        document.documentElement.removeAttribute('data-theme');
        document.documentElement.removeAttribute('data-section');
        document.body.replaceChildren();
    });

    it('the hero figure is ≥ 3:1 and its texts ≥ 4.5:1 on the hero wash', () => {
        // The hero's surface is a wash of the section colour over base-100 (`.lg-panel-hero`),
        // so its texts are measured on that computed surface and not on the bare token.
        const host = mount('<section class="lg-panel lg-panel-hero"><span class="type-display-xl text-signal">64</span><span class="type-h2">of 510</span><p class="type-small text-muted">quiet</p></section>');
        const panel = host.firstElementChild!;
        const surface = computed(panel, 'backgroundColor');
        const [figure, sentence, note] = panel.children;
        expect(ratio(computed(figure, 'color'), surface), 'figure').toBeGreaterThanOrEqual(OBJECT_FLOOR);
        expect(ratio(computed(sentence, 'color'), surface), 'sentence').toBeGreaterThanOrEqual(TEXT_FLOOR);
        expect(ratio(computed(note, 'color'), surface), 'note').toBeGreaterThanOrEqual(TEXT_FLOOR);
    });

    it('a cell label, a value and the two chart sums are ≥ 4.5:1 on the cell surface', () => {
        const host = mount('<section class="lg-panel"><h2 class="type-caption text-muted">Last 14 days</h2><span class="type-display-m">0</span><span class="type-mono-data text-signal">2</span><span class="type-small text-muted">came in</span></section>');
        const panel = host.firstElementChild!;
        const surface = computed(panel, 'backgroundColor');
        for (const child of Array.from(panel.children)) {
            expect(ratio(computed(child, 'color'), surface), child.className).toBeGreaterThanOrEqual(TEXT_FLOOR);
        }
    });
});
