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
  /** First of a new group. Draws a hairline above it, and nothing above the first item. */
  readonly opensGroup?: boolean;
}

/**
 * Seven destinations in three groups, separated by a hairline rather than by a heading: a
 * label per group would double the height of a rail that collapses to icons, and at that
 * width a heading has nothing to show.
 *
 * <p>The groups answer three different questions. <b>Today</b> is the morning's work in the
 * order it runs — what came in, what survived, and what is out with a client. <b>Over
 * time</b> is the same archive asked a longer question. <b>What goes in</b> is where the
 * offers come from, what is waiting to be let in by hand, and why the filter decides as it
 * does — the three screens that are about the pipeline's input rather than its output.
 *
 * <p>Offer detail is reached from the shortlist and is deliberately not here.
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
    { path: '/analytics', label: 'nav.analytics', icon: 'chart-line', opensGroup: true },
    { path: '/sources', label: 'nav.sources', icon: 'database', opensGroup: true },
    { path: '/review', label: 'nav.review', icon: 'file-text' },
    { path: '/rules', label: 'nav.rules', icon: 'sliders-horizontal' },
  ];
}
