import {afterNextRender, Directive, DOCUMENT, effect, ElementRef, inject, input, output, signal,} from '@angular/core';

/**
 * Fires when its element scrolls into view.
 *
 * <p>A sentinel below the list rather than a button, because the list is read by scrolling
 * and the next page should arrive before the scrolling stops. `rootMargin` asks for it a
 * screen early, so the reader does not meet the end and wait.
 *
 * <p>Set up after the first render: an observer attached before the element is laid out
 * fires immediately against a zero-sized box, which asks for page two before page one is
 * drawn. Guarded for the test environment, where there is no observer at all.
 */
@Directive({selector: '[lgLoadMore]'})
export class LoadMore {
    readonly reached = output<void>();

    /**
     * The box the sentinel is measured against. `null` is the window, which is right for a
     * page that scrolls as a whole; a list that scrolls inside its own column has to say so,
     * or the sentinel is compared against a window it never leaves — it intersects on the
     * first frame and on every frame after, and asks for the next page forever.
     *
     * <p>An input rather than a second selector: every caller writes `lgLoadMore` as a bare
     * attribute, a bare attribute binds as the empty string, and `strictTemplates` would then
     * reject every existing call site.
     *
     * <p>`rootMargin` is the other half of the reason. With the window as root and a scroller
     * in between, the margin expands the window's rectangle while the pane still clips
     * unmargined — the pre-load screen disappears without anything else looking wrong.
     */
    readonly root = input<HTMLElement | null>(null);

    private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly laidOut = signal(false);

    constructor() {
        inject(DOCUMENT);

        afterNextRender(() => this.laidOut.set(true));

        // An effect rather than a single read inside `afterNextRender`, so a root that arrives
        // or changes later re-arms the observer instead of leaving it silently on the window.
        effect((onCleanup) => {
            if (!this.laidOut() || typeof IntersectionObserver !== 'function') {
                return;
            }
            const observer = new IntersectionObserver(
                (entries) => {
                    if (entries.some((entry) => entry.isIntersecting)) {
                        this.reached.emit();
                    }
                },
                {root: this.root(), rootMargin: '600px 0px', threshold: 0},
            );
            observer.observe(this.element.nativeElement);
            onCleanup(() => observer.disconnect());
        });
    }
}
