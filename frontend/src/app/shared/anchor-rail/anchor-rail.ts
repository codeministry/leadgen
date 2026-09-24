import {
    afterRenderEffect,
    ChangeDetectionStrategy,
    Component,
    DOCUMENT,
    inject,
    input,
    signal,
} from '@angular/core';
import {RouterLink} from '@angular/router';
import {TranslocoPipe} from '@jsverse/transloco';

/** One entry of the rail: the id of the heading a link lands on, and the catalog key of its text. */
export interface AnchorSection {
    readonly id: string;
    readonly key: string;
}

/**
 * A screen's table of contents, beside the screen (spec 007).
 *
 * <p>The rail knows no screen. It takes the sections as an input, renders one in-page anchor
 * per section in a sticky left column, and wraps the screen's content as the column beside it,
 * so the two-column layout exists once, here, and not in four stylesheets. A screen that
 * declares no sections gets no column at all — the host falls back to a block and the content
 * takes the full width. Below 48rem the column is gone and the links are a row of chips under
 * the page header, one tap per section.
 *
 * <p>Each link is a router link with a fragment — not `href="#id"`, which the app's
 * `<base href="/">` would resolve to the dashboard — so the hash follows a click and a section
 * is a URL that can be copied or opened in a new tab. The router does not scroll (anchor
 * scrolling is off, and the router's own would ignore the sticky header), so the click handler
 * scrolls the heading into view, which lands it under the header through `.lg-anchor-target`'s
 * scroll margin, and moves focus onto it — the heading carries `tabindex="-1"` for that reason —
 * and never back to the rail. A page opened with a hash lands the same way, once its target
 * exists.
 *
 * <p>Which section is in view is read through an `IntersectionObserver` on the headings. It is
 * only the trigger: on each callback the current section is the last heading whose top has
 * passed the upper 40 % of the viewport, which is deterministic, ignores the observer's own
 * threshold bookkeeping, and holds while a long section scrolls with its heading gone. The
 * observer does not exist in jsdom and is dormant in a backgrounded tab, which is why the
 * hand-over is verified live and never in the unit tier.
 */
@Component({
    selector: 'lg-anchor-rail',
    imports: [RouterLink, TranslocoPipe],
    templateUrl: './anchor-rail.html',
    styleUrl: './anchor-rail.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: {'[class.bare]': 'sections().length === 0'},
})
export class AnchorRail {
    private readonly document = inject(DOCUMENT);

    /** The sections in document order. Empty renders no rail and no column. */
    readonly sections = input<readonly AnchorSection[]>([]);

    /** The nav's accessible name, translated by the screen (`nav.sections`). */
    readonly label = input.required<string>();

    /** The id of the section in view, or the one last navigated to. */
    protected readonly current = signal<string | null>(null);

    /** The hash the page was opened with, honoured once its target renders, then forgotten. */
    private pendingHash: string | null = null;

    constructor() {
        const hash = this.document.location?.hash ?? '';
        this.pendingHash = hash.length > 1 ? decodeURIComponent(hash.slice(1)) : null;

        afterRenderEffect((onCleanup) => {
            const targets = this.targets();
            const pending = this.pendingHash === null ? null : targets.find((target) => target.id === this.pendingHash);
            if (pending !== undefined && pending !== null) {
                this.pendingHash = null;
                this.land(pending, false);
            }
            if (targets.length === 0 || typeof IntersectionObserver === 'undefined') {
                return;
            }
            const observer = new IntersectionObserver(() => this.current.set(this.inView(targets)), {
                // The upper 40 % of the viewport is the band a heading is "current" in; the
                // callback fires when a heading enters or leaves it.
                rootMargin: '0px 0px -60% 0px',
                threshold: [0, 1],
            });
            for (const target of targets) {
                observer.observe(target);
            }
            onCleanup(() => observer.disconnect());
        });
    }

    protected jump(id: string): void {
        const heading = this.document.getElementById(id);
        if (heading !== null) {
            this.land(heading, true);
        }
    }

    /**
     * Scrolls the heading under the header and focuses it. A click glides (the operator's
     * word: "weiches Scrollen wäre besser"); a page opened with a hash jumps, because a glide
     * on arrival reads as the page moving on its own. Both stop gliding under
     * `prefers-reduced-motion`, which is the browser's own duration and needs no token.
     */
    private land(heading: HTMLElement, glide: boolean): void {
        this.current.set(heading.id);
        // jsdom has no `scrollIntoView` (the `scrollTo` trap in frontend/CLAUDE.md); the guard
        // keeps the unit tier alive and costs nothing in a browser.
        if (typeof (heading as Partial<HTMLElement>).scrollIntoView === 'function') {
            const reduced = this.document.defaultView?.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
            heading.scrollIntoView({block: 'start', behavior: glide && !reduced ? 'smooth' : 'auto'});
        }
        heading.focus({preventScroll: true});
    }

    private targets(): HTMLElement[] {
        return this.sections()
            .map((section) => this.document.getElementById(section.id))
            .filter((element): element is HTMLElement => element !== null);
    }

    private inView(targets: readonly HTMLElement[]): string {
        const view = this.document.defaultView;
        const height = view?.innerHeight ?? 0;
        const root = this.document.scrollingElement;
        // At the very bottom the last heading may never reach the band; the page has still
        // arrived at the last section.
        if (view && root && view.scrollY + height >= root.scrollHeight - 1) {
            return targets[targets.length - 1].id;
        }
        const line = height * 0.4;
        let current = targets[0].id;
        for (const target of targets) {
            if (target.getBoundingClientRect().top <= line) {
                current = target.id;
            }
        }
        return current;
    }
}
