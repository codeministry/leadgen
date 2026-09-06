import {ChangeDetectionStrategy, Component, computed, inject, OnInit} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {ActivatedRoute, ActivatedRouteSnapshot, NavigationEnd, Router, RouterOutlet,} from '@angular/router';
import {filter, map} from 'rxjs';
import {injectDispatch} from '@ngrx/signals/events';
import {statusEvents} from '@core/store/status.events';
import {AppHeader} from '../app-header/app-header';
import {AppNav} from '../app-nav/app-nav';

@Component({
    selector: 'lg-app-shell',
    imports: [AppHeader, AppNav, RouterOutlet],
    templateUrl: './app-shell.html',
    styleUrl: './app-shell.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppShell implements OnInit {
    private readonly dispatch = injectDispatch(statusEvents);
    private readonly router = inject(Router);
    private readonly route = inject(ActivatedRoute);

    /**
     * The URL, only as a reason to look again. Route data is read from the snapshot rather
     * than from an observable of its own, so something has to say when the snapshot changed
     * — and RxJS stays at the I/O boundary, which the router is.
     */
    private readonly url = toSignal(
        this.router.events.pipe(
            filter((event) => event instanceof NavigationEnd),
            map(() => this.router.url),
        ),
        {initialValue: this.router.url},
    );

    /**
     * The deepest activated route, which is the one that decides. `data` is inherited down
     * the chain — `paramsInheritanceStrategy` defaults to `'always'` — so a flag written once
     * on a parent is visible here even when a child is active.
     */
    private leaf(): ActivatedRouteSnapshot {
        this.url();
        let leaf = this.route.snapshot;
        while (leaf.firstChild !== null) {
            leaf = leaf.firstChild;
        }
        return leaf;
    }

    /**
     * Which measure the routed screen asked for: the reading default, the wide one, or the
     * whole window.
     *
     * From route data, not from the component: the element that caps the width is an
     * ancestor of the screen, so a custom property set on the child cannot reach it, and a
     * screen styling its own host would be centred differently depending on where it was
     * rendered. The decision belongs beside the route.
     *
     * One string rather than a flag per width. Three states on one axis written as two
     * booleans can be set to both at once, and then the stylesheet's order decides which
     * width a screen gets — silently, and only on the screen that carries both.
     */
    private readonly measure = computed(() => this.leaf().data['measure'] as string | undefined);

    protected readonly wide = computed(() => this.measure() === 'wide');

    protected readonly full = computed(() => this.measure() === 'full');

    /**
     * Whether the screen bounds itself to the viewport instead of growing the page.
     *
     * Beside `wide` and for the same reason: the element that has to stop scrolling is an
     * ancestor of the screen, so the screen cannot set it on itself. `.shell` keeps its
     * `min-height`, so every screen without this flag scrolls the document exactly as before.
     */
    protected readonly fill = computed(() => this.leaf().data['fill'] === true);

    ngOnInit(): void {
        this.dispatch.opened();
    }
}
