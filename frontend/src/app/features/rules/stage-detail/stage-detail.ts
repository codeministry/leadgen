import {ChangeDetectionStrategy, Component, computed, inject, input} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {FunnelView} from '@core/model/funnel';
import {PromptView} from '@core/model/prompt-view';
import {RulesView} from '@core/model/rules-view';
import {WorkflowSetting, WorkflowStage} from '@core/model/workflow';
import {FunnelRail} from '@shared/funnel-rail/funnel-rail';
import {Icon} from '@shared/icon/icon';
import {isAiStage} from '../ai-stage';
import {settingsView} from '../settings-view';
import {knockoutIcon, SUB_ICONS} from '../stage-marks';
import {AI_ICON, UNREAD_STAGE} from '../stage-rail/stage-rail';

/**
 * The right half of the rules screen: what the selected stage does, the keys it reads grouped
 * by the file they come from, and what only some stages have — FILTER's funnel, SCORE's
 * weights, bands and topics, and the prompt of every stage that calls a model.
 *
 * Fed entirely through inputs; the screen owns the stores and the selection.
 */
@Component({
    selector: 'lg-stage-detail',
    imports: [FunnelRail, Icon, TranslocoPipe],
    templateUrl: './stage-detail.html',
    styleUrl: './stage-detail.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StageDetail {
    /** The stage to show, or `unread` for the keys no stage reads. */
    readonly stage = input.required<WorkflowStage | typeof UNREAD_STAGE>();
    /** The workflow's `unread` keys; read only when `stage` is `unread`. */
    readonly unread = input<readonly WorkflowSetting[]>([]);
    readonly rules = input<RulesView | null>(null);
    readonly prompts = input<readonly PromptView[]>([]);
    readonly funnel = input<FunnelView | null>(null);

    private readonly transloco = inject(TranslocoService);
    private readonly lang = toSignal(this.transloco.langChanges$, {initialValue: this.transloco.getActiveLang()});
    /** The reader's own grouping for the working-list figures, following the language toggle. */
    private readonly formatter = computed(() => new Intl.NumberFormat(this.lang()));

    /** Null on the unread entry, so the template can narrow once. */
    protected readonly current = computed((): WorkflowStage | null => {
        const stage = this.stage();
        return stage === UNREAD_STAGE ? null : stage;
    });

    /** Per file, then per key prefix: the prefix once as a subheading, each key by its last segment. */
    protected readonly groups = computed(() => settingsView(this.current()?.settings ?? this.unread()));

    protected readonly prompt = computed((): PromptView | null => {
        const id = this.current()?.promptId ?? null;
        return id === null ? null : (this.prompts().find((prompt) => prompt.id === id) ?? null);
    });

    /**
     * Which key picked the model, for the band's second line (ISC-386). On the fallback the
     * stage's own key is the empty one and `modelKey` is `llm.models.scoring`, so both are
     * named: "own is empty, so the scoring judge answers (scoring)". The first key is
     * always the server's `ownKey`, never rebuilt here from the prompt id. Null when no model
     * answers, and then the line is not drawn.
     */
    protected readonly modelOrigin = computed((): {key: string; fallback: string | null} | null => {
        const prompt = this.prompt();
        if (prompt?.model == null || prompt.modelKey === null || prompt.ownKey === null) {
            return null;
        }
        return {key: prompt.ownKey, fallback: prompt.modelFallback ? prompt.modelKey : null};
    });

    /** A model takes part here (ISC-308): the pane opens with the AI band. Same predicate as the rail. */
    protected readonly isAi = computed(() => {
        const stage = this.current();
        return stage !== null && isAiStage(stage);
    });
    protected readonly aiIcon = AI_ICON;
    /** The icon of each section a canvas sub-node opens (ISC-406), the same one the sub-node shows. */
    protected readonly subIcons = SUB_ICONS;
    /** The icon of one knockout, the same one its sub-node carries on the canvas. */
    protected readonly knockoutIcon = knockoutIcon;

    protected readonly isFilter = computed(() => this.current()?.id === 'FILTER');
    protected readonly isScore = computed(() => this.current()?.id === 'SCORE');
    protected readonly isArchive = computed(() => this.current()?.id === 'ARCHIVE');

    /**
     * An ingest entry after the first. The server attaches the settings every source shares
     * to the first entry only, so an empty list here means "look there", not "reads nothing".
     */
    protected readonly isLaterIngest = computed(() => {
        const stage = this.current();
        return stage?.kind === 'ingest' && stage.settings.length === 0;
    });

    protected format(value: number): string {
        return this.formatter().format(value);
    }
}
