import {
    afterNextRender,
    ChangeDetectionStrategy,
    Component,
    effect,
    ElementRef,
    inject,
    Injector,
    input,
    output,
    viewChild,
} from '@angular/core';
import {TranslocoPipe} from '@jsverse/transloco';
import {FunnelView} from '@core/model/funnel';
import {PromptView} from '@core/model/prompt-view';
import {RulesView} from '@core/model/rules-view';
import {WorkflowSetting, WorkflowStage} from '@core/model/workflow';
import {Icon} from '@shared/icon/icon';
import {StageDetail} from '../stage-detail/stage-detail';
import {UNREAD_STAGE} from '../stage-rail/stage-rail';

let nextId = 0;

/**
 * The selected stage, opened at the right edge of the browser window (spec 017, ISC-393). It hosts
 * `lg-stage-detail` with the inputs 008 gave the detail pane, unchanged, and adds only what an
 * overlay needs: a heading that takes focus, a close button and Escape.
 *
 * A non-modal dialog, not a landmark: it takes focus and closes on Escape like a dialog, but the
 * canvas behind it stays usable, so another node can be picked while it is open. It never
 * navigates; `closed` goes to the screen, which owns the `stage` parameter and the focus return.
 */
@Component({
    selector: 'lg-stage-sheet',
    imports: [Icon, StageDetail, TranslocoPipe],
    templateUrl: './stage-sheet.html',
    styleUrl: './stage-sheet.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StageSheet {
    readonly stage = input.required<WorkflowStage | typeof UNREAD_STAGE>();
    readonly unread = input<readonly WorkflowSetting[]>([]);
    readonly rules = input<RulesView | null>(null);
    readonly prompts = input<readonly PromptView[]>([]);
    readonly funnel = input<FunnelView | null>(null);
    readonly closed = output();

    protected readonly headingId = `lg-stage-sheet-heading-${nextId++}`;
    private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');
    private readonly injector = inject(Injector);

    constructor() {
        // Every new stage moves focus to the heading, not only the first: picking another node
        // while the sheet is open is a new selection too. After render, so the heading exists.
        effect(() => {
            this.stage();
            afterNextRender(() => this.heading().nativeElement.focus(), {injector: this.injector});
        });
    }

    protected close(): void {
        this.closed.emit();
    }
}
