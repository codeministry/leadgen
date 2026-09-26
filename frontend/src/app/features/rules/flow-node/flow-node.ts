import {ChangeDetectionStrategy, Component, computed, inject, input, output} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {RouterLink} from '@angular/router';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {WorkflowStage} from '@core/model/workflow';
import {Icon} from '@shared/icon/icon';
import {LgIconName} from '@shared/icon/lucide-icons';
import {isAiStage} from '../ai-stage';
import {StageRunState} from '../run-state';
import {countVerbKey, formatStageCount} from '../stage-count';
import {AI_ICON, DONE_ICON, FAILED_ICON, RUNNING_ICON, costIcon, costLabelKey, stackWidth, stageLabelKey} from '../stage-marks';

/** The count chip's words and the grouped number they carry, or nothing to draw. */
interface CountChip {
    readonly key: string;
    readonly count: string;
}

/**
 * Formats seconds spent in the running stage as a duration a person reads at a glance
 * (`1:35`, never `95`) — kept beside the component and given its own cases (minutes-and-seconds
 * while the stage runs under an hour, hours added once it runs longer) rather than folded into a
 * template expression, so each case is testable without rendering anything (ISC-412).
 */
export function formatElapsed(totalSeconds: number): string {
    const seconds = Math.max(0, Math.floor(totalSeconds));
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const secs = seconds % 60;
    const pad = (value: number): string => value.toString().padStart(2, '0');
    return hours > 0 ? `${hours}:${pad(minutes)}:${pad(secs)}` : `${minutes}:${pad(secs)}`;
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
    /*
     * What the hovered legend entry does NOT name steps back, so the stages it does name stand out
     * (operator, 2026-09-26). A host style and not a rule in `flow-node.css`: that file may carry
     * no `opacity` at all — `flow-node.spec.ts` greps it, because the pending state must never be
     * drawn by fading a label — and this is a different thing, held only while a pointer rests on
     * a legend entry. It keys on `lit`, never on `state`, so the ban it sits beside still holds.
     * On the node itself rather than the canvas's wrapper, because a method call inside vflow's
     * projected template never marks that view dirty; the signal input does.
     */
    host: {'[style.opacity]': 'lit() === false ? 0.35 : null'},
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
    /**
     * This node's place against a run in flight, from `RunState.states`; null when no pass is
     * running, and also null for a stage the run state does not name.
     */
    readonly state = input<StageRunState | null>(null);
    /** Seconds spent in the running stage, or null. Non-null only on the node whose `state` is `running`. */
    readonly elapsed = input<number | null>(null);
    /**
     * Whether the hovered legend entry names this stage: true lights it, false dims it, null — no
     * entry under the pointer — leaves it alone (operator, 2026-09-26).
     */
    readonly lit = input<boolean | null>(null);
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
    protected readonly runningIcon = RUNNING_ICON;
    protected readonly doneIcon = DONE_ICON;

    /** Read reactively so a language switch regroups the number without a reload. */
    private readonly transloco = inject(TranslocoService);
    private readonly lang = toSignal(this.transloco.langChanges$, {initialValue: this.transloco.getActiveLang()});

    protected readonly labelKey = computed(() => stageLabelKey(this.stage()));
    protected readonly isAi = computed(() => isAiStage(this.stage()));

    /** The width stack, drawn by the predicate the legend's hover answer reads (ISC-402, ISC-405). */
    protected readonly stackWidth = computed(() => stackWidth(this.stage()));

    /**
     * The last run's count for this stage — null while a pass is in flight (ISC-412): a `count`
     * chip carries the last run's numbers, and while the running node shows a live elapsed time
     * in the same spot, every other node would otherwise go on showing a stale count that reads
     * as this run's. `state()` is non-null on every node once a pass is placed (`runState` fills
     * `states` for the whole order or not at all — see `run-state.ts`), so checking this node's
     * own state is exactly checking whether a pass is running at all.
     */
    protected readonly chip = computed((): CountChip | null => {
        if (this.state() !== null) {
            return null;
        }
        const count = this.count();
        const key = countVerbKey(this.stage());
        if (count === null || key === null) {
            return null;
        }
        return {key, count: formatStageCount(this.stage(), count, this.lang())};
    });

    /**
     * The state in words, as a catalog key (ISC-416): a screen reader gets none of the outline,
     * the glyph or the dashed border, so the state is also said. Null with no pass placed, which
     * is every node's case whenever `runState` names none — the node then carries nothing extra.
     */
    protected readonly stateKey = computed((): string | null => {
        const state = this.state();
        return state === null ? null : `rules.run.state.${state}`;
    });

    /**
     * The running node's elapsed time, formatted for display (ISC-412) — null on every node that
     * is not the one running, and also null on the running node before an `elapsed` value has
     * reached it.
     */
    protected readonly elapsedLabel = computed((): string | null => {
        if (this.state() !== 'running') {
            return null;
        }
        const elapsed = this.elapsed();
        return elapsed === null ? null : formatElapsed(elapsed);
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
