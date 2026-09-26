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

const SECTIONS = ['dashboard', 'shortlist', 'pipeline', 'analytics', 'sources', 'review', 'workflow'] as const;

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

    it('the band\'s second line and its key read at ≥ 4.5:1 on the band (ISC-386)', () => {
        // Rendered, not read off a token: the line and its <code> inherit the band's ink, and a
        // global rule on `code` or on `.type-mono-data` would show up here as a different colour.
        // The band's own declarations are repeated inline because core/ may not import the
        // rules screen; `stage-detail.css` sets exactly these two on `.ai-band`.
        const host = mount(
            '<p class="type-small" style="background-color: var(--lg-ai-surface); color: var(--color-base-content)">' +
                '<span class="ai-band-origin"><code class="type-mono-data">llm.models.fields</code> is empty, so the scoring judge answers</span>' +
                '</p>',
        );
        const band = host.firstElementChild!;
        const surface = painted(computed(band, 'backgroundColor'), token('--color-base-100'));
        const ground = `rgb(${surface.r}, ${surface.g}, ${surface.b})`;
        for (const el of [band.querySelector('.ai-band-origin')!, band.querySelector('code')!]) {
            expect(contrastRatio(painted(computed(el, 'color'), ground), surface), el.tagName).toBeGreaterThanOrEqual(TEXT_FLOOR);
        }
        document.body.replaceChildren();
    });
});

describe.each(THEMES)('%s: the run marker reads and stays clear of its neighbours (ISC-415)', theme => {
    beforeEach(() => useTheme(theme));
    afterEach(() => document.documentElement.removeAttribute('data-theme'));

    it('defines --lg-run', () => {
        expect(token('--lg-run')).toMatch(/^oklch\(/);
    });

    it('is at least 3:1 on the node surface and the canvas surface', () => {
        for (const ground of ['--color-base-100', '--color-base-200']) {
            expect(ratio(token('--lg-run'), token(ground)), `--lg-run on ${ground}`).toBeGreaterThanOrEqual(OBJECT_FLOOR);
        }
    });

    it('resolves to a colour distinct from --lg-signal and --lg-ai, pairwise', () => {
        const run = resolveColour(token('--lg-run'));
        const signal = resolveColour(token('--lg-signal'));
        const ai = resolveColour(token('--lg-ai'));
        const same = (a: typeof run, b: typeof run) => a.r === b.r && a.g === b.g && a.b === b.b;
        expect(same(run, signal), 'run vs signal').toBe(false);
        expect(same(run, ai), 'run vs ai').toBe(false);
        expect(same(signal, ai), 'signal vs ai').toBe(false);
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

    it('the stage: figure, sentence, muted lines and held-back dots read on --lg-stage', () => {
        // The hero stands on `.lg-stage`, a dark surface in both themes, which
        // re-points the tokens its content reads. Measured on the stage's own colour: the glows
        // are light over it and never the ground under a text.
        const host = mount(
            '<section class="lg-stage"><span class="text-signal">82</span>'
            + '<span class="type-h1">offers</span>'
            + '<p class="text-muted">quiet</p><svg><circle style="fill: var(--lg-stage-dot)"/></svg></section>',
        );
        const stage = host.firstElementChild!;
        const surface = computed(stage, 'backgroundColor');
        const [figure, sentence, note, svg] = Array.from(stage.children);
        expect(ratio(computed(figure, 'color'), surface), 'figure').toBeGreaterThanOrEqual(TEXT_FLOOR);
        expect(ratio(computed(sentence, 'color'), surface), 'sentence').toBeGreaterThanOrEqual(TEXT_FLOOR);
        expect(ratio(computed(note, 'color'), surface), 'muted').toBeGreaterThanOrEqual(TEXT_FLOOR);
        expect(ratio(getComputedStyle(svg.firstElementChild!).fill, surface), 'held-back dot').toBeGreaterThanOrEqual(OBJECT_FLOOR);
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

describe.each(THEMES)('%s: the shortlist card reads on both of its surfaces (ISC-383)', theme => {
    beforeEach(() => useTheme(theme));
    afterEach(() => document.documentElement.removeAttribute('data-theme'));

    // The card is base-100, and the open one is the selection wash over it. The status is
    // text in the primary's text twin (the bare primary read 4.35:1 on the light hover wash), the flags are text in the warning twin, the facts and the source
    // are muted; each has to read on both. The icons take their text's colour, except two that
    // carry their own: the lift's trending-up in success and the topic's tag in the primary.
    // Those two are objects, not text, so 3:1 is their floor.
    const grounds = () => {
        const base = token('--color-base-100');
        const wash = painted(token('--lg-selected-surface'), base);
        const hover = painted(token('--lg-selected-surface-hover'), base);
        return [
            {label: 'base-100', css: base},
            {label: 'selected', css: `rgb(${wash.r}, ${wash.g}, ${wash.b})`},
            {label: 'selected hover', css: `rgb(${hover.r}, ${hover.g}, ${hover.b})`},
        ];
    };

    it('the status, the flags and the muted lines are ≥ 4.5:1', () => {
        for (const ground of grounds()) {
            for (const colour of ['--lg-primary-text', '--lg-warning-text', '--lg-muted', '--color-base-content']) {
                expect(ratio(token(colour), ground.css), `${colour} on ${ground.label}`).toBeGreaterThanOrEqual(TEXT_FLOOR);
            }
        }
    });

    it('the lift and topic icons are ≥ 3:1', () => {
        for (const ground of grounds()) {
            for (const colour of ['--color-success', '--color-primary']) {
                expect(ratio(token(colour), ground.css), `${colour} on ${ground.label}`).toBeGreaterThanOrEqual(OBJECT_FLOOR);
            }
        }
    });
});

describe.each(THEMES)('%s: the rules flow graph reads (ISC-400)', theme => {
    beforeEach(() => useTheme(theme));
    afterEach(() => document.documentElement.removeAttribute('data-theme'));

    // Read off the tokens the rules screen's stylesheets name, because core/ may not import the
    // rules screen: `flow-node.css` (the card in base-100, the selection wash over it, the count
    // chip in base-200), `flow-canvas.css` (the canvas in base-200, which is also handed to
    // the graph library as its background and checked rendered in `flow-canvas.browser.spec.ts`, with
    // edges and arrows in `--lg-muted` through currentColor), `flow-legend.css` (the strip on the
    // page) and `stage-sheet.css` (a `.lg-panel`, so base-100). The selection is a wash, so it is
    // composited over the card before anything is measured on it.
    const opaque = (name: string, over: string): string => {
        const c = painted(token(name), token(over));
        return `rgb(${c.r}, ${c.g}, ${c.b})`;
    };
    const grounds = () => ({
        node: token('--color-base-100'),
        selected: opaque('--lg-selected-surface', '--color-base-100'),
        chip: token('--color-base-200'),
        canvas: token('--color-base-200'),
        sheet: token('--color-base-100'),
        page: token('--color-base-200'),
        // The unread chip's count pill. It reads on the warning's text twin, not on
        // `--color-warning`: near-black on a 62 %-light warm hue turned the figure to mud on
        // screen (operator, 2026-09-26). The status chip carries its figure as plain text.
        unreadPill: token('--lg-warning-text'),
    });
    type Ground = keyof ReturnType<typeof grounds>;

    const measure = (pairs: [string, Ground][], floor: number): string[] => {
        const g = grounds();
        return pairs
            .map(([colour, ground]) => ({label: `${colour} on ${ground}`, ratio: ratio(token(colour), g[ground])}))
            .filter(p => p.ratio < floor)
            .map(p => `${p.label}: ${p.ratio.toFixed(2)}`);
    };

    it('node phase, name, ×N and chip, the legend and the sheet are ≥ 4.5:1', () => {
        expect(
            measure(
                [
                    ['--lg-muted', 'node'], // phase line, ×N
                    ['--lg-muted', 'selected'],
                    ['--color-base-content', 'node'], // name, sub-row
                    ['--color-base-content', 'selected'],
                    ['--color-base-content', 'chip'], // count chip
                    ['--color-base-content', 'page'], // legend labels
                    ['--lg-muted', 'page'], // legend title, ×N in the legend
                    ['--lg-muted', 'sheet'], // sheet heading
                    ['--color-base-content', 'sheet'], // sheet body
                    ['--color-base-100', 'unreadPill'], // the unread count, white on the text twin
                ],
                TEXT_FLOOR,
            ),
        ).toEqual([]);
    });

    it('cost, AI and failed markers, the selection, edges and arrows are ≥ 3:1', () => {
        expect(
            measure(
                [
                    ['--lg-muted', 'node'], // cost icons
                    ['--lg-muted', 'selected'],
                    ['--lg-ai', 'node'], // AI sparkle and edge
                    ['--lg-ai', 'selected'],
                    ['--color-error', 'node'], // failed triangle
                    ['--color-error', 'selected'],
                    ['--color-primary', 'canvas'], // the selected node's border against the canvas
                    ['--color-primary', 'selected'],
                    ['--lg-muted', 'canvas'], // edges and arrow markers
                    ['--lg-muted', 'page'], // legend cost icon
                    ['--lg-ai', 'page'], // legend AI icon
                    ['--color-error', 'page'], // legend failed icon
                ],
                OBJECT_FLOOR,
            ),
        ).toEqual([]);
    });
});
