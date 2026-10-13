/**
 * Where a chat popover opens (ISC-524): from its trigger, end-aligned, clamped on both axes against the
 * visual viewport with a 1rem margin, its height capped by the room on its side.
 *
 * <p>The width is the stylesheet's — gutter to gutter below 48rem, a capped column from 48rem — and this
 * reads it back once the panel is shown, so one rule decides the width and one the position. Run it after
 * `showPopover()` and in the same task: the panel is then laid out, and nothing has been painted yet.
 *
 * <p>`window.visualViewport`, not the window: a soft keyboard and a pinch zoom shrink the visual viewport
 * and leave the layout viewport as it was, and a panel clamped against the latter ends under the keyboard.
 *
 * <p>It writes custom properties and no real ones, so the stylesheet decides how to use them
 * (`shared/popover/anchor-for.ts` gives the reason). `--lg-anchor-x` is the left edge, `--lg-anchor-max-w`
 * the widest the panel may be, `--lg-anchor-room` the tallest; `--lg-anchor-bottom` (above) or
 * `--lg-anchor-y` (below) holds the edge that faces the trigger, so a panel whose content grows while open
 * grows away from it.
 */

/**
 * Which side of the trigger the panel opens on, which of its edges lines up with the trigger's (`end`, the
 * default, for a trigger at the end of its row; `start` for one at the start), and the gap between them in px.
 */
export interface PlaceOptions {
    readonly side?: 'above' | 'below';
    readonly align?: 'start' | 'end';
    readonly gap?: number;
}

const DEFAULT_GAP_PX = 8;

/** Every property `place` writes. */
const ANCHOR_PROPERTIES = ['--lg-anchor-x', '--lg-anchor-y', '--lg-anchor-bottom', '--lg-anchor-max-w', '--lg-anchor-room'] as const;

/**
 * Forgets the last placement, before a panel is measured for the next one: a height capped by the room of
 * an earlier open, or a width by an earlier viewport, would otherwise be read back as the panel's own.
 * Both edges go too: both set, the panel would stretch between them.
 */
export function unplace(panel: HTMLElement): void {
    for (const name of ANCHOR_PROPERTIES) panel.style.removeProperty(name);
}

/** The clamp's margin: 1rem, read off the root, which is not 16px in this app. */
function marginPx(): number {
    return parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
}

function clamp(value: number, min: number, max: number): number {
    return Math.min(Math.max(value, min), Math.max(min, max));
}

function px(value: number): string {
    return `${Math.round(value)}px`;
}

/** Places `panel` against `trigger` once. */
export function place(trigger: HTMLElement, panel: HTMLElement, opts: PlaceOptions = {}): void {
    const gap = opts.gap ?? DEFAULT_GAP_PX;
    const margin = marginPx();
    const view = window.visualViewport;
    const vx = view?.offsetLeft ?? 0;
    const vy = view?.offsetTop ?? 0;
    const vw = view?.width ?? window.innerWidth;
    const vh = view?.height ?? window.innerHeight;
    const box = trigger.getBoundingClientRect();
    const style = panel.style;

    const maxWidth = Math.max(0, vw - 2 * margin);
    style.setProperty('--lg-anchor-max-w', px(maxWidth));
    // Read after the cap is written: the read lays the panel out with it.
    const width = Math.min(panel.getBoundingClientRect().width, maxWidth);
    const start = opts.align === 'start' ? box.left : box.right - width;
    style.setProperty('--lg-anchor-x', px(clamp(start, vx + margin, vx + vw - margin - width)));

    if (opts.side === 'below') {
        const top = clamp(box.bottom + gap, vy + margin, vy + vh - margin);
        style.setProperty('--lg-anchor-y', px(top));
        style.setProperty('--lg-anchor-room', px(vy + vh - margin - top));
        return;
    }
    // `bottom` resolves against the layout viewport, which is what the client height is.
    const edge = clamp(box.top - gap, vy + margin, vy + vh - margin);
    style.setProperty('--lg-anchor-bottom', px(document.documentElement.clientHeight - edge));
    style.setProperty('--lg-anchor-room', px(edge - vy - margin));
}

/**
 * Places `panel` now and again on every resize or scroll of the visual viewport (a soft keyboard, a pinch,
 * a rotation) and of the window, and whenever the trigger may have moved without them — once per frame at
 * most. Returns the function that stops it.
 */
export function track(trigger: HTMLElement, panel: HTMLElement, opts: PlaceOptions = {}): () => void {
    let frame = 0;
    const again = (): void => {
        if (frame !== 0) return;
        frame = requestAnimationFrame(() => {
            frame = 0;
            place(trigger, panel, opts);
        });
    };
    place(trigger, panel, opts);
    const view = window.visualViewport;
    view?.addEventListener('resize', again);
    view?.addEventListener('scroll', again);
    window.addEventListener('resize', again);
    // The trigger also moves without the viewport: a drawer that slides in, or that the app widens a
    // tick after a resize. The end of a slide, and a box around the trigger changing size, place again.
    document.addEventListener('animationend', again, true);
    document.addEventListener('transitionend', again, true);
    // A scroller around the trigger (a chat thread) carries it along without moving the viewport.
    document.addEventListener('scroll', again, {capture: true, passive: true});
    const boxes = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(again);
    for (let box = trigger.parentElement; box !== null; box = box.parentElement) boxes?.observe(box);
    return () => {
        cancelAnimationFrame(frame);
        view?.removeEventListener('resize', again);
        view?.removeEventListener('scroll', again);
        window.removeEventListener('resize', again);
        document.removeEventListener('animationend', again, true);
        document.removeEventListener('transitionend', again, true);
        document.removeEventListener('scroll', again, {capture: true});
        boxes?.disconnect();
    };
}
