import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { ShellStore } from '@core/shell/shell.store';
import { Icon } from '@shared/icon/icon';
import { LgIconName } from '@shared/icon/lucide-icons';

interface NavItem {
  readonly path: string;
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
  imports: [Icon, RouterLink, RouterLinkActive],
  templateUrl: './app-nav.html',
  styleUrl: './app-nav.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppNav {
  protected readonly shell = inject(ShellStore);

  protected readonly items: readonly NavItem[] = [
    { path: '/dashboard', label: 'Dashboard', icon: 'layout-dashboard' },
    { path: '/shortlist', label: 'Shortlist', icon: 'list-checks' },
    { path: '/pipeline', label: 'Pipeline', icon: 'columns-3' },
    { path: '/review', label: 'Review', icon: 'file-text' },
    { path: '/sources', label: 'Sources', icon: 'database' },
    { path: '/rules', label: 'Rules', icon: 'sliders-horizontal' },
  ];
}
