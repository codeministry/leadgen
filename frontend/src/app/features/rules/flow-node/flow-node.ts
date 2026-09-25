import {ChangeDetectionStrategy, Component, computed, inject, input, output} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {RouterLink} from '@angular/router';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {WorkflowStage} from '@core/model/workflow';
import {Icon} from '@shared/icon/icon';
import {LgIconName} from '@shared/icon/lucide-icons';
import {isAiStage} from '../ai-stage';
import {countVerbKey, formatStageCount} from '../stage-count';
import {AI_ICON, FAILED_ICON, costIcon, costLabelKey, stageLabelKey} from '../stage-marks';

/** The count chip's words and the grouped number they carry, or nothing to draw. */
interface CountChip {
    readonly key: string;
    readonly count: string;
}

/**
 * One stage of the workflow drawn as a card of the flow graph: its phase, its name, what it
 * costs, whether a model takes part, whether the last run failed there, and what that run did
 * there ("−12,548 held back").
 *
 * Fed entirely through inputs, like the rail. The card is a link that writes the screen's
 * `stage` query parameter, so the selection survives a reload and the back button without this
 * component holding any state.
 */
@Component({
    selector: 'lg-flow-node',
    imports: [Icon, RouterLink, TranslocoPipe],
    templateUrl: './flow-node.html',
    styleUrl: './flow-node.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FlowNode {
    readonly stage = input.required<WorkflowStage>();
    /** The id of the phase the stage belongs to, named on the card as `rules.phase.<id>`. */
    readonly phaseId = input.required<string>();
    /**
     * What the last run did at this stage, from `stageCounts`. Null means no number: no chip,
     * and never a zero standing in for "not measured".
     */
    readonly count = input<number | null>(null);
    /** The last run's `stages[]` marks this stage `FAILED`. */
    readonly failed = input(false);
    /** The detail pane shows this stage. */
    readonly selected = input(false);
    /**
     * The stage opens into sub-nodes on the canvas (knockouts, SCORE's blocks, a prompt). The
     * node draws the toggle; whether it is open is the canvas's view state, passed back in.
     */
    readonly expandable = input(false);
    readonly expanded = input(false);
    /** The toggle was pressed: the id of the stage whose sub-nodes should open or close. */
    readonly toggled = output<string>();

    protected readonly aiIcon = AI_ICON;
    protected readonly failedIcon = FAILED_ICON;

    /** Read reactively so a language switch regroups the number without a reload. */
    private readonly transloco = inject(TranslocoService);
    private readonly lang = toSignal(this.transloco.langChanges$, {initialValue: this.transloco.getActiveLang()});

    protected readonly labelKey = computed(() => stageLabelKey(this.stage()));
    protected readonly isAi = computed(() => isAiStage(this.stage()));

    /**
     * How many adverts the stage works on at once, only when that is more than one: a width of
     * one, or none, draws the card exactly as a stage no width bounds (ISC-402).
     */
    protected readonly stackWidth = computed((): number | null => {
        const value = this.stage().width?.value ?? null;
        return value !== null && value > 1 ? value : null;
    });

    protected readonly chip = computed((): CountChip | null => {
        const count = this.count();
        const key = countVerbKey(this.stage());
        if (count === null || key === null) {
            return null;
        }
        return {key, count: formatStageCount(this.stage(), count, this.lang())};
    });

    protected toggle(event: MouseEvent): void {
        // A sibling of the link already, so nothing navigates; stopped as well so the canvas's
        // own node handlers do not read the press as a click on the node.
        event.stopPropagation();
        this.toggled.emit(this.stage().id);
    }

    protected costIcon(costClass: string): LgIconName {
        return costIcon(costClass);
    }

    protected costLabel(costClass: string): string {
        return costLabelKey(this.stage(), costClass);
    }
}
