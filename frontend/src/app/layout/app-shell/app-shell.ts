import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { injectDispatch } from '@ngrx/signals/events';
import { statusEvents } from '@core/store/status.events';
import { AppHeader } from '../app-header/app-header';
import { AppNav } from '../app-nav/app-nav';

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
    { initialValue: this.router.url },
  );

  /**
   * Whether the routed screen asked for the wide measure.
   *
   * From route data, not from the component: the element that caps the width is an
   * ancestor of the screen, so a custom property set on the child cannot reach it, and a
   * screen styling its own host would be centred differently depending on where it was
   * rendered. The decision belongs beside the route.
   */
  protected readonly wide = computed(() => {
    this.url();
    let leaf = this.route.snapshot;
    while (leaf.firstChild !== null) {
      leaf = leaf.firstChild;
    }
    return leaf.data['wide'] === true;
  });

  ngOnInit(): void {
    this.dispatch.opened();
  }
}
