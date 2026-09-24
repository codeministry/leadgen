import {ChangeDetectionStrategy, Component, computed, inject, OnInit} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {ActivatedRoute, ActivatedRouteSnapshot, NavigationEnd, Router, RouterOutlet,} from '@angular/router';
import {filter, map} from 'rxjs';
import {injectDispatch} from '@ngrx/signals/events';
import {statusEvents} from '@core/store/status.events';
import {isSection, Section} from '@core/theme/section.model';
import {AppHeader} from '../app-header/app-header';
import {ToastStack} from '../toast-stack/toast-stack';

@Component({
    selector: 'lg-app-shell',
  imports: [AppHeader, RouterOutlet, ToastStack],
    templateUrl: './app-shell.html',
    styleUrl: './app-shell.css',
    // The section colour is keyed off this attribute in `tokens.css`; on the host so the
    // header, the nav, the toast stack and the routed screen all sit inside it.
    host: {'[attr.data-section]': 'section()'},
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
     * Which measure the routed screen asked for: the shell's own bound, or the whole window.
     *
     * From route data, not from the component: the element that caps the width is an
     * ancestor of the screen, so a custom property set on the child cannot reach it, and a
     * screen styling its own host would be centred differently depending on where it was
     * rendered. The decision belongs beside the route.
     *
     * A string rather than a boolean, even at two states. There used to be a third, `wide`,
     * and it was measured to change nothing — both screens that asked for it cap their own
     * host lower. A name is what let that be found and removed; a `full` flag beside a
     * `wide` flag is two booleans on one axis, and then the stylesheet's order decides.
     */
    private readonly measure = computed(() => this.leaf().data['measure'] as string | undefined);

    protected readonly full = computed(() => this.measure() === 'full');

    /**
     * Whether the screen bounds itself to the viewport instead of growing the page.
     *
     * Beside `full` and for the same reason: the element that has to stop scrolling is an
     * ancestor of the screen, so the screen cannot set it on itself. `.shell` keeps its
     * `min-height`, so every screen without this flag scrolls the document exactly as before.
     */
    protected readonly fill = computed(() => this.leaf().data['fill'] === true);

    /**
     * Which of the seven destinations the routed screen belongs to, or nothing while the
     * redirect is still resolving. Read from the leaf, so a child route inherits its
     * parent's section for free — the detail under the shortlist is the shortlist's colour,
     * the same detail under the pipeline is the pipeline's.
     */
    protected readonly section = computed<Section | null>(() => {
        const value = this.leaf().data['section'];
        return isSection(value) ? value : null;
    });

    ngOnInit(): void {
        this.dispatch.opened();
    }
}
