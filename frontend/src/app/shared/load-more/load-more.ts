import { DOCUMENT, DestroyRef, Directive, ElementRef, afterNextRender, inject, output } from '@angular/core';

/**
 * Fires when its element scrolls into view.
 *
 * <p>A sentinel below the list rather than a button, because the list is read by scrolling
 * and the next page should arrive before the scrolling stops. `rootMargin` asks for it a
 * screen early, so the reader does not meet the end and wait.
 *
 * <p>Set up in `afterNextRender`: an observer attached before the element is laid out fires
 * immediately against a zero-sized box, which asks for page two before page one is drawn.
 * Guarded for the test environment, where there is no observer at all.
 */
@Directive({ selector: '[lgLoadMore]' })
export class LoadMore {
  readonly reached = output<void>();

  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private observer: IntersectionObserver | null = null;

  constructor() {
    inject(DOCUMENT);

    afterNextRender(() => {
      if (typeof IntersectionObserver !== 'function') {
        return;
      }
      this.observer = new IntersectionObserver(
        (entries) => {
          if (entries.some((entry) => entry.isIntersecting)) {
            this.reached.emit();
          }
        },
        { rootMargin: '600px 0px', threshold: 0 },
      );
      this.observer.observe(this.element.nativeElement);
    });

    inject(DestroyRef).onDestroy(() => this.observer?.disconnect());
  }
}
