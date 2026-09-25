import {ChangeDetectionStrategy, Component, input} from '@angular/core';
import {RouterLink} from '@angular/router';
import {TranslocoPipe} from '@jsverse/transloco';
import {Icon} from '@shared/icon/icon';
import {LgIconName} from '@shared/icon/lucide-icons';
import {AI_ICON, COST_ICONS, FAILED_ICON} from '../stage-marks';
import {UNREAD_STAGE} from '../stage-rail/stage-rail';

/** One icon a flow node can carry, and the catalog key of its words. */
interface IconEntry {
    readonly kind: 'cost' | 'ai' | 'failed';
    readonly icon: LgIconName;
    readonly labelKey: string;
}

/**
 * Derived from the constants `lg-flow-node` reads, so a new cost class or marker cannot reach a
 * node without reaching the legend too. The model class keeps its general word here: the legend
 * explains the icon, a node names the call it makes.
 */
const ICON_ENTRIES: readonly IconEntry[] = [
    ...Object.entries(COST_ICONS).map(([costClass, icon]): IconEntry => ({kind: 'cost', icon, labelKey: `rules.cost.${costClass}`})),
    {kind: 'ai', icon: AI_ICON, labelKey: 'rules.ai.legend'},
    {kind: 'failed', icon: FAILED_ICON, labelKey: 'rules.stageFailed'},
];

/**
 * The canvas legend (ISC-398): every marker a flow node can draw, drawn the way the node draws
 * it, beside its words — and, set apart from the markers because it is no step of the run, the
 * entry for the keys nothing reads, which opens them in the sheet the way a node opens its stage.
 */
@Component({
    selector: 'lg-flow-legend',
    imports: [Icon, RouterLink, TranslocoPipe],
    templateUrl: './flow-legend.html',
    styleUrl: './flow-legend.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FlowLegend {
    /** The `stage` the screen has selected, so the unread entry can show it is the open one. */
    readonly selected = input<string | null>(null);

    protected readonly entries = ICON_ENTRIES;
    protected readonly unread = UNREAD_STAGE;
}
