import {ChangeDetectionStrategy, Component} from '@angular/core';
import {RouterLink, RouterLinkActive} from '@angular/router';
import {TranslocoPipe} from '@jsverse/transloco';
import {Icon} from '@shared/icon/icon';
import {LgIconName} from '@shared/icon/lucide-icons';

interface NavItem {
    readonly path: string;
    /** A catalog key, not a sentence: nothing user-facing is written in TypeScript. */
    readonly label: string;
    readonly icon: LgIconName;
    /** First of a new group. Draws a hairline above it, and nothing above the first item. */
    readonly opensGroup?: boolean;
}

/**
 * Six destinations in three groups, separated by a hairline rather than by a heading: the
 * row is icons only below 64rem and a bottom bar below 48rem, and at neither width does a
 * group heading have anywhere to go.
 *
 * <p>The dashboard stands alone, as the overview of everything after it. <b>Today</b> is the
 * morning's work in the order it runs — what survived and what is out with a client. The
 * last group is the same archive asked a longer question, followed by the pipeline's input:
 * why the filter decides as it does and where the offers come from. Sources moved back here
 * from the settings panel (the operator's call, 2026-09-24).
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
    protected readonly items: readonly NavItem[] = [
        {path: '/dashboard', label: 'nav.dashboard', icon: 'layout-dashboard'},
        {path: '/shortlist', label: 'nav.shortlist', icon: 'list-checks', opensGroup: true},
        {path: '/pipeline', label: 'nav.pipeline', icon: 'columns-3'},
        {path: '/analytics', label: 'nav.analytics', icon: 'chart-line', opensGroup: true},
        {path: '/rules', label: 'nav.rules', icon: 'sliders-horizontal'},
        {path: '/sources', label: 'nav.sources', icon: 'database'},
    ];
}
