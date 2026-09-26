import {ChangeDetectionStrategy, Component, input} from '@angular/core';
import {RouterLink} from '@angular/router';
import {TranslocoPipe} from '@jsverse/transloco';
import {Icon} from '@shared/icon/icon';
import {LgIconName} from '@shared/icon/lucide-icons';
import {AI_ICON, COST_ICONS, FAILED_ICON} from '../stage-marks';

/** One icon a flow node can carry, and the catalog key of its words. */
interface IconEntry {
    readonly kind: 'cost' | 'ai' | 'failed';
    /** The marker's id in `markersOf`'s vocabulary: the cost class, `ai` or `failed`. */
    readonly id: string;
    readonly icon: LgIconName;
    /** The long sentence, which the entry carries for a screen reader and for the pointer. */
    readonly labelKey: string;
    /**
     * The word on screen. The long one ran the legend over two lines and pushed the strip into
     * the drawing (operator, 2026-09-26): a legend is a key, not a manual, and the sentence is
     * one hover or one screen reader away.
     */
    readonly shortKey: string;
}

/** The width stack's id in `markersOf`'s vocabulary; its entry is drawn apart from the icons. */
const WIDTH_MARKER = 'width';

/**
 * Derived from the constants `lg-flow-node` reads, so a new cost class or marker cannot reach a
 * node without reaching the legend too. The model class keeps its general word here: the legend
 * explains the icon, a node names the call it makes.
 */
const ICON_ENTRIES: readonly IconEntry[] = [
    ...Object.entries(COST_ICONS).map(
        ([costClass, icon]): IconEntry => ({
            kind: 'cost',
            id: costClass,
            icon,
            labelKey: `rules.cost.${costClass}`,
            shortKey: `rules.legend.short.${costClass}`,
        }),
    ),
    // The AI marker's own short word already exists: it is what the node's sparkle is named.
    {kind: 'ai', id: 'ai', icon: AI_ICON, labelKey: 'rules.ai.legend', shortKey: 'rules.ai.marker'},
    {kind: 'failed', id: 'failed', icon: FAILED_ICON, labelKey: 'rules.stageFailed', shortKey: 'rules.legend.short.failed'},
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
    /**
     * The markers of the hovered or focused stage (ISC-405), from `markersOf`: those entries are
     * highlighted and the rest faded. Null, no stage under the reader, leaves every entry as is.
     * A faded entry keeps its words in the DOM and in the accessibility tree; the highlight is
     * never the only way a marker is named.
     */
    readonly active = input<ReadonlySet<string> | null>(null);

    protected readonly entries = ICON_ENTRIES;
    protected readonly widthMarker = WIDTH_MARKER;

    protected isActive(id: string): boolean {
        return this.active()?.has(id) ?? false;
    }

    protected isFaded(id: string): boolean {
        const active = this.active();
        return active !== null && !active.has(id);
    }
}
