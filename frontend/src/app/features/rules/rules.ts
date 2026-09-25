import {ChangeDetectionStrategy, Component, computed, inject, input, OnInit} from '@angular/core';
import {injectDispatch} from '@ngrx/signals/events';
import {TranslocoPipe} from '@jsverse/transloco';
import {WorkflowStage} from '@core/model/workflow';
import {configEvents} from '@core/store/config.events';
import {ConfigStore} from '@core/store/config.store';
import {IngestStore} from '@core/store/ingest.store';
import {ShortlistStore} from '@core/store/shortlist.store';
import {Badge} from '@shared/badge/badge';
import {PageHeader} from '@shared/page-header/page-header';
import {FlowCanvas} from './flow-canvas/flow-canvas';
import {StageDetail} from './stage-detail/stage-detail';
import {StageRail, UNREAD_STAGE} from './stage-rail/stage-rail';
import {stageCounts} from './stage-count';

/**
 * The pipeline as a split view: the phases and their stages on the left, the selected stage
 * on the right.
 *
 * Replaces spec 007's anchor rail on this screen (spec 008, § Decisions): the stages are the
 * navigation, so a second list of in-page anchors would be two answers to one question.
 */
@Component({
    selector: 'lg-rules',
    imports: [Badge, FlowCanvas, PageHeader, StageDetail, StageRail, TranslocoPipe],
    templateUrl: './rules.html',
    styleUrl: './rules.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Rules implements OnInit {
    private readonly dispatch = injectDispatch(configEvents);
    protected readonly store = inject(ConfigStore);
    /**
     * `rulesOpened` asks for the last run and the funnel, and a store nobody has created does
     * not answer. The rail's counts are read from the last run alone (spec 008 § Decisions);
     * the funnel is what FILTER's detail pane draws (ISC-289).
     */
    private readonly ingest = inject(IngestStore);
    private readonly shortlist = inject(ShortlistStore);

    /**
     * The `stage` query parameter, bound by the router. The selection lives in the URL and
     * nowhere else, so a reload reads it back and the back button restores it (ISC-288): the
     * rail's entries are links that push one history entry each.
     *
     * The transform is not optional. Router input binding writes `undefined` for a parameter
     * absent from the URL rather than leaving the declared default.
     */
    readonly stage = input('', {transform: (value: string | undefined) => value ?? ''});

    private readonly stages = computed(
        (): readonly WorkflowStage[] => this.store.workflow()?.phases.flatMap((phase) => phase.stages) ?? [],
    );

    /**
     * The id the URL names, matched without regard to case so a hand-typed `?stage=filter`
     * finds `FILTER`; `unread` for the keys nothing reads; the first stage for none and for a
     * value the server did not name. An unknown value is left standing in the URL rather than
     * rewritten: the screen shows what it shows for none, and the next click replaces it.
     */
    protected readonly selected = computed((): string | null => {
        const wanted = this.stage().toLowerCase();
        if (wanted === UNREAD_STAGE) {
            return UNREAD_STAGE;
        }
        const stages = this.stages();
        return (stages.find((stage) => stage.id.toLowerCase() === wanted) ?? stages[0])?.id ?? null;
    });

    /** What the detail pane shows: the selected stage itself, or the unread entry. */
    protected readonly detail = computed((): WorkflowStage | typeof UNREAD_STAGE | null => {
        const selected = this.selected();
        if (selected === UNREAD_STAGE) {
            return UNREAD_STAGE;
        }
        return this.stages().find((stage) => stage.id === selected) ?? null;
    });

    /** What the last run left at each stage; an absent count is shown as none, never as a zero. */
    protected readonly counts = computed(() => stageCounts(this.store.workflow(), this.ingest.lastRun()));

    /** The last run as the rail reads it: the run-less line, and which stage failed. */
    protected readonly lastRun = computed(() => this.ingest.lastRun());

    /** The six filter stages' removals, drawn on FILTER's detail pane. */
    protected readonly funnel = computed(() => this.shortlist.funnel());

    ngOnInit(): void {
        this.dispatch.rulesOpened();
    }
}
