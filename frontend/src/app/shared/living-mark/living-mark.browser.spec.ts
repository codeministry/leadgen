import {ComponentFixture, TestBed} from '@angular/core/testing';
import {cdp, page} from 'vitest/browser';
import {LivingMark, LivingMarkFrame} from './living-mark';

/**
 * The living mark in a real renderer (ISC-464): every frame under both themes, read back
 * through computed style rather than through the template, because the claim is about what
 * the browser paints — the ring on the theme's primary in every frame, the dots on whatever
 * `color` the host carries, and the frame named once, on the host, as `data-frame`.
 *
 * <p>The helpers are local on purpose: `shared/` imports nothing from the layers above it,
 * so the contrast spec's resolver under `core/theme` is out of reach from here.
 */

const THEMES = ['lg-light', 'lg-dark'] as const;
const FRAMES: readonly LivingMarkFrame[] = ['rest', 'working', 'speaking', 'halted'];

/** The visible arc per frame, in the ring's 360-unit path length (design.md, the static stills). */
const ARC: Record<LivingMarkFrame, number> = {rest: 270, working: 270, speaking: 315, halted: 240};

/** Resolve any CSS colour the browser understands to sRGBA, through a 1×1 canvas. */
function rgba(css: string): string {
    const canvas = document.createElement('canvas');
    canvas.width = 1;
    canvas.height = 1;
    const ctx = canvas.getContext('2d', {willReadFrequently: true});
    if (!ctx) throw new Error('no 2D context: this spec is running outside a browser');
    ctx.clearRect(0, 0, 1, 1);
    ctx.fillStyle = css;
    ctx.fillRect(0, 0, 1, 1);
    return Array.from(ctx.getImageData(0, 0, 1, 1).data).join(',');
}

function token(name: string): string {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

async function mount(frame: LivingMarkFrame, hostColour?: string): Promise<HTMLElement> {
    // A standalone component needs no module, so one test can create several marks.
    const fixture = TestBed.createComponent(LivingMark);
    fixture.componentRef.setInput('frame', frame);
    const host = fixture.nativeElement as HTMLElement;
    if (hostColour) host.style.color = hostColour;
    document.body.appendChild(host);
    fixture.detectChanges();
    await fixture.whenStable();
    return host;
}

/** The typed `CDPSession` carries no members; the provider's own session has `send()`. */
function setReducedMotion(reduced: boolean): Promise<unknown> {
    const session = cdp() as unknown as {send(method: string, params: {features: readonly {name: string; value: string}[]}): Promise<unknown>};
    return session.send('Emulation.setEmulatedMedia', {features: reduced ? [{name: 'prefers-reduced-motion', value: 'reduce'}] : []});
}

/** A dot counts when the frame shows it: painted, and not faded out. */
function visibleDots(host: HTMLElement): SVGCircleElement[] {
    return Array.from(host.querySelectorAll<SVGCircleElement>('circle')).filter((dot) => {
        const style = getComputedStyle(dot);
        return style.fill !== 'none' && Number(style.opacity) > 0 && style.display !== 'none';
    });
}

describe('LivingMark in the browser (ISC-464)', () => {
    // The drawing is asserted on the static stills, which is what the reduced-motion
    // preference shows: without it the working dots and the speaking arc are in flight, and
    // a read would catch them at whatever phase the clock happens to be (ISC-466 below).
    beforeEach(() => setReducedMotion(true));

    afterEach(async () => {
        await setReducedMotion(false);
        document.documentElement.removeAttribute('data-theme');
        document.body.querySelectorAll(':scope > lg-living-mark').forEach((el) => el.remove());
    });

    for (const theme of THEMES) {
        describe(`under ${theme}`, () => {
            beforeEach(() => document.documentElement.setAttribute('data-theme', theme));

            it('writes each of the four frames on its host, four distinct words', async () => {
                const words: string[] = [];
                for (const frame of FRAMES) {
                    const host = await mount(frame);
                    expect(host.getAttribute('data-frame')).toBe(frame);
                    words.push(host.getAttribute('data-frame') ?? '');
                }
                expect(new Set(words).size).toBe(4);
            });

            for (const frame of FRAMES) {
                it(`paints the ring on --color-primary and its dots on the host's colour in ${frame}`, async () => {
                    const host = await mount(frame);
                    const svg = host.querySelector('svg');
                    expect(svg?.getAttribute('aria-hidden')).toBe('true');
                    expect(svg?.getAttribute('viewBox')).toBe('0 0 32 32');

                    const ring = host.querySelector<SVGPathElement>('.ring');
                    expect(ring).not.toBeNull();
                    const ringStyle = getComputedStyle(ring as SVGPathElement);
                    expect(rgba(ringStyle.stroke)).toBe(rgba(token('--color-primary')));
                    expect(ringStyle.fill).toBe('none');

                    // The resting host is the brand mark: its colour defaults to the signal.
                    const hostColour = getComputedStyle(host).color;
                    expect(rgba(hostColour)).toBe(rgba(token('--lg-signal')));
                    for (const dot of visibleDots(host)) {
                        expect(rgba(getComputedStyle(dot).fill)).toBe(rgba(hostColour));
                    }

                    const dash = ringStyle.strokeDasharray.split(/[ ,]+/).map((part) => parseFloat(part));
                    expect(dash[0]).toBe(ARC[frame]);
                    expect(dash[0] + dash[1]).toBe(360);
                });

                it(`lets a host raise its own colour on the dots in ${frame}, never on the ring`, async () => {
                    const host = await mount(frame, 'var(--color-info)');
                    const ring = host.querySelector<SVGPathElement>('.ring') as SVGPathElement;
                    expect(rgba(getComputedStyle(ring).stroke)).toBe(rgba(token('--color-primary')));

                    const info = rgba(token('--color-info'));
                    expect(rgba(getComputedStyle(host).color)).toBe(info);
                    for (const dot of visibleDots(host)) {
                        expect(rgba(getComputedStyle(dot).fill)).toBe(info);
                    }
                });
            }

            it('tells the frames apart by their dots: lead at rest, three in flow, core alone, none at halt', async () => {
                const count = async (frame: LivingMarkFrame) => visibleDots(await mount(frame)).length;
                expect(await count('rest')).toBe(2);
                expect(await count('working')).toBe(4);
                expect(await count('speaking')).toBe(1);
                expect(await count('halted')).toBe(0);
            });

            it('draws the halted core hollow, as an outline in the ring\'s primary', async () => {
                const host = await mount('halted');
                const core = getComputedStyle(host.querySelector('.core') as SVGCircleElement);
                expect(core.fill).toBe('none');
                expect(rgba(core.stroke)).toBe(rgba(token('--color-primary')));
                expect(parseFloat(core.strokeWidth)).toBe(2.25);
            });
        });
    }

    it('starts from the brand\'s own arc, so rest is the brand mark to the path', async () => {
        const host = await mount('rest');
        const ring = host.querySelector('.ring') as SVGPathElement;
        expect(ring.getAttribute('d')?.startsWith('M16 3a13 13 0 1 0 13 13')).toBe(true);
        expect(ring.getAttribute('pathLength')).toBe('360');
        const core = host.querySelector('.core') as SVGCircleElement;
        expect([core.getAttribute('cx'), core.getAttribute('cy'), core.getAttribute('r')]).toEqual(['16', '16', '4.6']);
        const lead = host.querySelector('.lead') as SVGCircleElement;
        expect([lead.getAttribute('cx'), lead.getAttribute('cy'), lead.getAttribute('r')]).toEqual(['27.5', '6.5', '3']);
    });

    it('defaults to rest at 20 px and follows the size input on both axes', async () => {
        const host = await mount('rest');
        expect(host.getAttribute('data-frame')).toBe('rest');
        const svg = host.querySelector('svg') as SVGSVGElement;
        expect(svg.getBoundingClientRect().width).toBe(20);
        expect(svg.getBoundingClientRect().height).toBe(20);

        const fixture = TestBed.createComponent(LivingMark);
        fixture.componentRef.setInput('size', 16);
        document.body.appendChild(fixture.nativeElement as HTMLElement);
        fixture.detectChanges();
        await fixture.whenStable();
        const small = (fixture.nativeElement as HTMLElement).querySelector('svg') as SVGSVGElement;
        expect(small.getBoundingClientRect().width).toBe(16);
        expect(small.getBoundingClientRect().height).toBe(16);
        expect((fixture.nativeElement as HTMLElement).getAttribute('data-frame')).toBe('rest');
    });
});

/** A motion token in milliseconds, whether the stylesheet wrote it in `ms` or in `s`. */
function millis(name: string): number {
    const value = token(name);
    return value.endsWith('ms') ? parseFloat(value) : parseFloat(value) * 1000;
}

/** Which parts of the mark are animated right now, by their class, in document order. */
function animatedParts(host: HTMLElement): string[] {
    return host
        .getAnimations({subtree: true})
        .map((animation) => ((animation.effect as KeyframeEffect | null)?.target as Element | null)?.getAttribute('class') ?? '?')
        .sort();
}

function centre(el: Element): {x: number; y: number} {
    const box = el.getBoundingClientRect();
    return {x: box.left + box.width / 2, y: box.top + box.height / 2};
}

/**
 * Park an animation `ms` into an iteration, whatever negative delay it starts on. A negative
 * current time would sit in the before phase (the boundary clamps at 0) and show the still, so
 * a delayed animation is parked two iterations later, which keeps an alternate one's direction.
 */
function park(animation: Animation, ms: number): void {
    const timing = (animation.effect as KeyframeEffect).getTiming();
    const delay = Number(timing.delay ?? 0);
    animation.pause();
    animation.currentTime = ms + delay + (delay < 0 ? 2 * Number(timing.duration) : 0);
}

describe('LivingMark in motion (ISC-466)', () => {
    const SIZE = 64;
    let fixture: ComponentFixture<LivingMark>;
    let host: HTMLElement;

    beforeEach(async () => {
        fixture = TestBed.createComponent(LivingMark);
        fixture.componentRef.setInput('size', SIZE);
        host = fixture.nativeElement as HTMLElement;
        document.body.appendChild(host);
        fixture.detectChanges();
        await fixture.whenStable();
    });

    afterEach(async () => {
        await setReducedMotion(false);
        document.body.querySelectorAll(':scope > lg-living-mark').forEach((el) => el.remove());
    });

    /** Move to a frame and let its arc transition and lead fade run out before reading. */
    async function show(frame: LivingMarkFrame): Promise<void> {
        fixture.componentRef.setInput('frame', frame);
        fixture.detectChanges();
        await fixture.whenStable();
        await new Promise((resolve) => setTimeout(resolve, millis('--lg-reveal-duration') + 50));
    }

    for (const reduced of [false, true]) {
        const run = reduced ? 'with prefers-reduced-motion: reduce' : 'without a motion preference';

        it(`animates ${reduced ? 'no frame' : 'working and speaking only'} ${run}, and four frames paint four pictures`, async () => {
            await setReducedMotion(reduced);
            const parts: Partial<Record<LivingMarkFrame, string[]>> = {};
            const shots: string[] = [];
            for (const frame of FRAMES) {
                await show(frame);
                parts[frame] = animatedParts(host);
                shots.push(await page.screenshot({element: host, save: false}));
            }

            expect(parts).toEqual(
                reduced
                    ? {rest: [], working: [], speaking: [], halted: []}
                    : {rest: [], working: ['dot flow flow-1', 'dot flow flow-2', 'dot flow flow-3'], speaking: ['ring'], halted: []},
            );
            expect(new Set(shots).size).toBe(4);
        });
    }

    it('lets each working dot fall from the lead\'s place through the opening into the core', async () => {
        await show('working');
        const lead = centre(host.querySelector('.lead') as Element);
        const core = centre(host.querySelector('.core') as Element);
        const fall = millis('--lg-sift-duration');
        const animations = host.getAnimations({subtree: true});
        expect(animations).toHaveLength(3);
        // One user unit is SIZE / 32 px; a dot is on its mark within half of one.
        const near = (a: {x: number; y: number}, b: {x: number; y: number}) =>
            expect(Math.hypot(a.x - b.x, a.y - b.y)).toBeLessThan(SIZE / 64);
        for (const animation of animations) {
            const dot = (animation.effect as KeyframeEffect).target as Element;
            // 1 ms in rather than 0: parked exactly on an iteration boundary, float rounding can
            // land a hair before it and read the previous fall's end.
            park(animation, 1);
            near(centre(dot), lead);
            park(animation, fall);
            near(centre(dot), core);
        }
    });

    it('sweeps the speaking arc from the brand\'s 270° toward closing at 320°, and back', async () => {
        await show('speaking');
        const ring = host.querySelector('.ring') as SVGPathElement;
        const [sweep] = host.getAnimations({subtree: true});
        const arc = () => parseFloat(getComputedStyle(ring).strokeDasharray.split(/[ ,]+/)[0]);
        const quarterPulse = millis('--lg-pulse-duration') / 4;
        expect((sweep.effect as KeyframeEffect).getComputedTiming().duration).toBe(quarterPulse);
        park(sweep, 0);
        expect(arc()).toBeCloseTo(270, 0);
        park(sweep, quarterPulse - 1);
        expect(arc()).toBeCloseTo(320, 0);
        park(sweep, 2 * quarterPulse - 1);
        expect(arc()).toBeCloseTo(270, 0);
    });
});

describe('LivingMark, ambient at rest (the header chat button)', () => {
    const SIZE = 64;

    afterEach(async () => {
        await setReducedMotion(false);
        document.body.querySelectorAll(':scope > lg-living-mark').forEach((el) => el.remove());
    });

    async function mountAmbient(frame: LivingMarkFrame = 'rest'): Promise<HTMLElement> {
        const fixture = TestBed.createComponent(LivingMark);
        fixture.componentRef.setInput('size', SIZE);
        fixture.componentRef.setInput('frame', frame);
        fixture.componentRef.setInput('motion', 'ambient');
        const host = fixture.nativeElement as HTMLElement;
        document.body.appendChild(host);
        fixture.detectChanges();
        await fixture.whenStable();
        return host;
    }

    /** The idle layer on a part: the infinite one, whatever name the compiler gave its keyframes. */
    function idle(host: HTMLElement, part: string): Animation {
        const found = host
            .getAnimations({subtree: true})
            .find((a) => ((a.effect as KeyframeEffect).target as Element).classList.contains(part) && (a.effect as KeyframeEffect).getTiming().iterations === Infinity);
        if (!found) throw new Error(`no idle animation on .${part}`);
        return found;
    }

    it('writes the motion on its host, and still is the default', async () => {
        const fixture = TestBed.createComponent(LivingMark);
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).getAttribute('data-motion')).toBe('still');
        expect((await mountAmbient()).getAttribute('data-motion')).toBe('ambient');
    });

    it('draws itself in, then turns the whole mark once at the end of every idle period', async () => {
        const host = await mountAmbient();
        const parts = new Set(animatedParts(host));
        expect(parts).toEqual(new Set(['body', 'ring', 'dot lead', 'dot core']));

        const period = millis('--lg-mark-idle-period');
        const turn = idle(host, 'body');
        expect((turn.effect as KeyframeEffect).getComputedTiming().duration).toBe(period);
        const ring = host.querySelector('.ring') as SVGPathElement;
        const arc = () => parseFloat(getComputedStyle(ring).strokeDasharray.split(/[ ,]+/)[0]);
        const arcIdle = idle(host, 'ring');
        // The intro layer wins while it plays; take it out so the idle layer is what is read.
        host.getAnimations({subtree: true}).filter((a) => (a.effect as KeyframeEffect).getTiming().iterations !== Infinity).forEach((a) => a.finish());

        // Most of the period the mark is the brand mark, standing.
        park(arcIdle, period / 2);
        expect(arc()).toBeCloseTo(270, 0);
        park(arcIdle, period * 0.98);
        expect(arc()).toBeCloseTo(330, 0);

        const body = host.querySelector('.body') as SVGGElement;
        park(turn, period / 2);
        expect(getComputedStyle(body).transform).toMatch(/^(none|matrix\(1, 0, 0, 1, 0, 0\))$/);
        park(turn, period * 0.98);
        expect(getComputedStyle(body).transform).not.toMatch(/^(none|matrix\(1, 0, 0, 1, 0, 0\))$/);
    });

    it('keeps ambient to rest: a working mark moves only its flow dots', async () => {
        const host = await mountAmbient('working');
        await new Promise((resolve) => setTimeout(resolve, millis('--lg-reveal-duration') + 50));
        expect(animatedParts(host)).toEqual(['dot flow flow-1', 'dot flow flow-2', 'dot flow flow-3']);
    });

    it('stands still under prefers-reduced-motion, and a host\'s turn does not play', async () => {
        await setReducedMotion(true);
        const host = await mountAmbient();
        host.style.setProperty('--lg-living-mark-turn', '-360deg');
        expect(animatedParts(host)).toEqual([]);
        expect(getComputedStyle(host.querySelector('.mark') as Element).transform).toBe('none');
    });

    it('turns by the angle its host sets, and back when the host takes it away', async () => {
        const host = await mountAmbient();
        const mark = host.querySelector('.mark') as SVGSVGElement;
        host.style.setProperty('--lg-living-mark-turn', '-90deg');
        await new Promise((resolve) => setTimeout(resolve, millis('--lg-mark-turn-duration') + 100));
        // rotate(-90deg) is matrix(0, -1, 1, 0, 0, 0), up to float noise.
        const [a, b] = getComputedStyle(mark).transform.match(/-?[\d.e-]+/g)!.map(Number);
        expect(a).toBeCloseTo(0, 3);
        expect(b).toBeCloseTo(-1, 3);
    });
});
