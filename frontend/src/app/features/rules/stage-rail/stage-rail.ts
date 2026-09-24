import {ChangeDetectionStrategy, Component, computed, input} from '@angular/core';
import {RouterLink} from '@angular/router';
import {TranslocoPipe} from '@jsverse/transloco';
import {LastRunView} from '@core/model/last-run';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';
import {Icon} from '@shared/icon/icon';
import {LgIconName} from '@shared/icon/lucide-icons';
import {isAiStage} from '../ai-stage';

/** The `stage` value of the entry after DIGEST, the keys no stage reads. */
export const UNREAD_STAGE = 'unread';

/**
 * One icon per cost class, spelled out as a literal map. The meaning travels in the icon's
 * accessible name, never in a colour: `free`, `model`, `network` and `file` are what a stage
 * costs, not how good it is, and the signal colour is reserved for survivors.
 */
export const COST_ICONS: Readonly<Record<string, LgIconName>> = {
    free: 'list-checks',
    model: 'message-circle-question',
    network: 'external-link',
    file: 'file-text',
};

/** The AI marker (ISC-308) and the failed-stage marker, named once for the rail and its legend. */
export const AI_ICON: LgIconName = 'sparkles';
export const FAILED_ICON: LgIconName = 'triangle-alert';

/** One legend entry (ISC-310): the icon a stage row draws, the catalog key of its words. */
interface LegendEntry {
    readonly kind: 'cost' | 'ai' | 'failed';
    readonly icon: LgIconName;
    readonly labelKey: string;
}

/**
 * Derived from the same constants the rows read, so a new cost class or marker cannot reach
 * a row without reaching the legend too.
 */
const LEGEND: readonly LegendEntry[] = [
    ...Object.entries(COST_ICONS).map(([costClass, icon]): LegendEntry => ({kind: 'cost', icon, labelKey: `rules.cost.${costClass}`})),
    {kind: 'ai', icon: AI_ICON, labelKey: 'rules.ai.legend'},
    {kind: 'failed', icon: FAILED_ICON, labelKey: 'rules.stageFailed'},
];

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
 * The left half of the rules screen: the pipeline's phases as one numbered flow, top to
 * bottom, with their stages in the order the server sent them, and one entry after the last
 * phase for the keys nothing reads. The order is carried by the `<ol>` and the numbered
 * headings; the arrows between phases only draw it and are hidden from assistive technology.
 *
 * Fed entirely through inputs. The selection is the screen's `stage` query parameter, so every
 * entry is a link that writes it — a reload and the back button then keep the selection
 * without this component holding any state at all.
 */
@Component({
    selector: 'lg-stage-rail',
    imports: [Icon, RouterLink, TranslocoPipe],
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
    protected readonly legend = LEGEND;

    /** Stage ids the recorded run's `stages[]` marks `FAILED` — the same strings as `stage.id`. */
    protected readonly failedStages = computed((): ReadonlySet<string> => {
        const run = this.lastRun();
        if (run === null) {
            return new Set();
        }
        return new Set(run.stages.filter((stage) => stage.status === 'FAILED').map((stage) => stage.stage));
    });

    /**
     * An ingest entry is named by its source, which is configuration and not a label; every
     * other stage by a catalog key, because the server sends a closed id and the browser holds
     * the words.
     */
    protected labelKey(stage: WorkflowStage): string | null {
        return stage.kind === 'ingest' ? null : `rules.stage.${stage.id.toLowerCase()}`;
    }

    /** A model takes part here; the same predicate the detail pane draws its band from. */
    protected isAi(stage: WorkflowStage): boolean {
        return isAiStage(stage);
    }

    /**
     * What the icon says on this row. The model class covers two different calls, a prompt to a
     * language model and an embedding, so the row names the one it makes; the legend keeps the
     * general word, because it explains the icon rather than a stage.
     */
    protected costLabel(stage: WorkflowStage, costClass: string): string {
        if (costClass !== 'model') {
            return `rules.cost.${costClass}`;
        }
        return stage.promptId === null ? 'rules.cost.modelEmbed' : 'rules.cost.modelPrompt';
    }

    protected costIcon(costClass: string): LgIconName {
        return COST_ICONS[costClass] ?? 'ellipsis';
    }

    protected phaseIcon(phaseId: string): LgIconName {
        return PHASE_ICONS[phaseId] ?? 'ellipsis';
    }

    protected count(id: string): string | number | null {
        return this.counts()[id] ?? null;
    }

    protected failed(id: string): boolean {
        return this.failedStages().has(id);
    }
}
