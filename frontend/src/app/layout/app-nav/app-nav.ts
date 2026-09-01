import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { ShellStore } from '@core/shell/shell.store';
import { Icon } from '@shared/icon/icon';
import { LgIconName } from '@shared/icon/lucide-icons';

interface NavItem {
  readonly path: string;
  /** A catalog key, not a sentence: nothing user-facing is written in TypeScript. */
  readonly label: string;
  readonly icon: LgIconName;
}

/**
 * Six destinations, in the order the work runs: what came in, what survived, what
 * is out with a client, what is waiting to be let in by hand, where it all came
 * from, and why the filter decided that way. Offer detail is reached from the
 * shortlist and is deliberately not here.
 */
@Component({
  selector: 'lg-app-nav',
  imports: [Icon, RouterLink, RouterLinkActive, TranslocoPipe],
  templateUrl: './app-nav.html',
  styleUrl: './app-nav.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppNav {
  protected readonly shell = inject(ShellStore);

  protected readonly items: readonly NavItem[] = [
    { path: '/dashboard', label: 'nav.dashboard', icon: 'layout-dashboard' },
    { path: '/shortlist', label: 'nav.shortlist', icon: 'list-checks' },
    { path: '/pipeline', label: 'nav.pipeline', icon: 'columns-3' },
    { path: '/review', label: 'nav.review', icon: 'file-text' },
    { path: '/sources', label: 'nav.sources', icon: 'database' },
    { path: '/rules', label: 'nav.rules', icon: 'sliders-horizontal' },
  ];
}
