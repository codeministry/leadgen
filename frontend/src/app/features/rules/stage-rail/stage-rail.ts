import {ChangeDetectionStrategy, Component, computed, inject, input} from '@angular/core';
import {NgTemplateOutlet} from '@angular/common';
import {toSignal} from '@angular/core/rxjs-interop';
import {RouterLink} from '@angular/router';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {LastRunView} from '@core/model/last-run';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';
import {Icon} from '@shared/icon/icon';
import {LgIconName} from '@shared/icon/lucide-icons';
import {isAiStage} from '../ai-stage';
import {countVerbKey, formatStageCount} from '../stage-count';
import {AI_ICON, COST_ICONS, FAILED_ICON, costIcon, costLabelKey, failedStageIds, stageLabelKey} from '../stage-marks';

/** The `stage` value of the entry after DIGEST, the keys no stage reads. */
export const UNREAD_STAGE = 'unread';

/** Re-exported for the rail's spec and callers that named them here before the flow graph shared them. */
export {AI_ICON, COST_ICONS, FAILED_ICON};

/**
 * One icon per phase, beside its heading and therefore decorative: the heading's words carry
 * the meaning. A phase id the server adds later falls back to a neutral glyph, never to none.
 */
const PHASE_ICONS: Readonly<Record<string, LgIconName>> = {
    read: 'inbox',
    sort: 'funnel',
    understand: 'scan-search',
    judge: 'scale',
    hand: 'package',
};

/**
 * The workflow as a vertical flow pipe (ISC-395): the phases as numbered headings on one
 * continuous spine, their stages as compact pills hanging off it in the order the server sent
 * them, the ingest sources bracketed as parallel branches that merge into the stage after them,
 * and one entry after the last phase for the keys nothing reads. The order is carried by the DOM
 * (the `<ol>`, the numbered headings, the links in run order); the spine, the branches and the
 * merge only draw it and are hidden from assistive technology, which hears the fan-in once as a
 * sentence instead.
 *
 * Fed entirely through inputs. The selection is the screen's `stage` query parameter, so every
 * entry is a link that writes it — a reload and the back button then keep the selection
 * without this component holding any state at all.
 */
@Component({
    selector: 'lg-stage-rail',
    imports: [Icon, NgTemplateOutlet, RouterLink, TranslocoPipe],
    templateUrl: './stage-rail.html',
    styleUrl: './stage-rail.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StageRail {
    readonly workflow = input.required<WorkflowView>();
    /** The id of the stage the detail pane shows, or `unread`. */
    readonly selected = input<string | null>(null);
    /**
     * What the last run left at each stage, by stage id. Absent or null means no number to
     * show — never a zero standing in for "not measured".
     */
    readonly counts = input<Readonly<Record<string, string | number | null>>>({});

    /**
     * The recorded run, or null before one has ever finished (ISC-290). Read for two things
     * only: whether the rail says so in words instead of drawing counts, and which stage, if
     * any, that run's `stages[]` marks `FAILED`. The parent binds `[lastRun]="…"`; `rules.ts`
     * feeds it `ingest.lastRun()`, the same signal `stageCounts` already reads.
     */
    readonly lastRun = input<LastRunView | null>(null);

    protected readonly unread = UNREAD_STAGE;
    protected readonly aiIcon = AI_ICON;
    protected readonly failedIcon = FAILED_ICON;

    /** Stage ids the recorded run's `stages[]` marks `FAILED` — the same strings as `stage.id`. */
    protected readonly failedStages = computed(() => failedStageIds(this.lastRun()));

    /** An ingest entry is named by its source, every other stage by a catalog key. */
    protected labelKey(stage: WorkflowStage): string | null {
        return stageLabelKey(stage);
    }

    /** A model takes part here; the same predicate the detail pane draws its band from. */
    protected isAi(stage: WorkflowStage): boolean {
        return isAiStage(stage);
    }

    /** What the icon says on this row: the call a model stage makes, the class otherwise. */
    protected costLabel(stage: WorkflowStage, costClass: string): string {
        return costLabelKey(stage, costClass);
    }

    protected costIcon(costClass: string): LgIconName {
        return costIcon(costClass);
    }

    protected phaseIcon(phaseId: string): LgIconName {
        return PHASE_ICONS[phaseId] ?? 'ellipsis';
    }

    /** Read reactively so a language switch regroups the numbers without a reload. */
    private readonly transloco = inject(TranslocoService);
    private readonly lang = toSignal(this.transloco.langChanges$, {initialValue: this.transloco.getActiveLang()});

    /**
     * The fan-in (ISC-395): how many sources the bracket draws and the stage they merge into —
     * the first stage after the last source in run order, DEDUPE on the live workflow. Null
     * without a source or without a stage after them, so the sentence is never half true.
     */
    protected readonly fanIn = computed((): {count: number; targetKey: string} | null => {
        const all = this.workflow().phases.flatMap((phase) => phase.stages);
        const count = all.filter((stage) => stage.kind === 'ingest').length;
        const target = all.slice(all.map((stage) => stage.kind).lastIndexOf('ingest') + 1)[0];
        if (count === 0 || target === undefined) {
            return null;
        }
        // The stage after the last source is never itself a source, so it always has a catalog key.
        return {count, targetKey: stageLabelKey(target) ?? target.id};
    });

    protected sources(stages: readonly WorkflowStage[]): WorkflowStage[] {
        return stages.filter((stage) => stage.kind === 'ingest');
    }

    protected spineStages(stages: readonly WorkflowStage[]): WorkflowStage[] {
        return stages.filter((stage) => stage.kind !== 'ingest');
    }

    /**
     * The chip the canvas draws for the same count — its verb and the grouped number — or the
     * bare value for a count that has no verb, or nothing without a count at all.
     */
    protected chip(stage: WorkflowStage): {key: string | null; count: string} | null {
        const value = this.counts()[stage.id] ?? null;
        if (value === null) {
            return null;
        }
        const key = countVerbKey(stage);
        if (key === null || typeof value !== 'number') {
            return {key: null, count: String(value)};
        }
        return {key, count: formatStageCount(stage, value, this.lang())};
    }

    protected failed(id: string): boolean {
        return this.failedStages().has(id);
    }
}
