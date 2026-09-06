import {ChangeDetectionStrategy, Component, ElementRef, signal, viewChild} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {LoadMore} from './load-more';

interface Observed {
    root: Element | Document | null;
    rootMargin: string | undefined;
}

/**
 * jsdom ships no `IntersectionObserver` — which is what the directive's `typeof` guard is
 * for. The stub records what it was constructed with, because the root is the whole point
 * of these specs and nothing about it is observable from the DOM.
 */
function stubObserver(): Observed[] {
    const seen: Observed[] = [];

    class Stub {
        constructor(_callback: IntersectionObserverCallback, init?: IntersectionObserverInit) {
            seen.push({root: init?.root ?? null, rootMargin: init?.rootMargin});
        }

        readonly observed: Element[] = [];

        observe(element: Element): void {
            this.observed.push(element);
        }

        disconnect(): void {
            this.observed.length = 0;
        }
    }

    Object.defineProperty(globalThis, 'IntersectionObserver', {
        configurable: true,
        writable: true,
        value: Stub,
    });
    return seen;
}

@Component({
    selector: 'lg-load-more-host',
    imports: [LoadMore],
    template: `
    <section #pane>
      <div lgLoadMore [root]="inPane() ? (pane ?? null) : null"></div>
    </section>
  `,
    changeDetection: ChangeDetectionStrategy.OnPush,
})
class Host {
    readonly inPane = signal(true);
    readonly pane = viewChild<ElementRef<HTMLElement>>('pane');
}

describe('LoadMore', () => {
    afterEach(() => {
        Reflect.deleteProperty(globalThis, 'IntersectionObserver');
    });

    async function render(inPane: boolean): Promise<Observed> {
        const seen = stubObserver();
        await TestBed.configureTestingModule({imports: [Host]}).compileComponents();
        const fixture = TestBed.createComponent(Host);
        fixture.componentInstance.inPane.set(inPane);
        fixture.detectChanges();
        // `afterNextRender` is what arms the observer; without this it has not run yet.
        await fixture.whenStable();
        return seen[seen.length - 1]!;
    }

    it('measures the sentinel against the pane it was given', async () => {
        // A list that scrolls inside its own column has to say so: against the window the
        // sentinel intersects on the first frame and on every frame after, and pages the whole
        // archive without anybody scrolling.
        const observed = await render(true);

        expect(observed.root).toBeInstanceOf(HTMLElement);
        expect((observed.root as HTMLElement).tagName).toBe('SECTION');
    });

    it('falls back to the window when nothing is given', async () => {
        // Every existing caller writes `lgLoadMore` as a bare attribute, and a page that scrolls
        // as a whole is measured against the window.
        const observed = await render(false);

        expect(observed.root).toBeNull();
        expect(observed.rootMargin).toBe('600px 0px');
    });
});
