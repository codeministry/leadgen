import {
    afterNextRender,
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
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
import {CurrentRunView} from '@core/model/current-run';
import {LastRunView} from '@core/model/last-run';
import {RunDetail} from '../run-detail/run-detail';
import {RUN_STATUS} from '../run-state';
import {StageDetail} from '../stage-detail/stage-detail';
import {UNREAD_STAGE} from '../stage-rail/stage-rail';

let nextId = 0;

/** How long the section a sub-node opened stays marked. */
const HIGHLIGHT_MS = 1600;

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
    imports: [Icon, RunDetail, StageDetail, TranslocoPipe],
    templateUrl: './stage-sheet.html',
    styleUrl: './stage-sheet.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StageSheet {
    readonly stage = input.required<WorkflowStage | typeof UNREAD_STAGE | typeof RUN_STATUS>();
    /** The run status's two payloads and its clock; read only when `stage` is the run sentinel. */
    readonly current = input<CurrentRunView | null>(null);
    readonly lastRun = input<LastRunView | null>(null);
    readonly elapsed = input<number | null>(null);
    readonly removalLabels = input<ReadonlyMap<string, string>>(new Map());

    /** The sentinel the template branches on, so the string is spelled once. */
    protected readonly runStatus = RUN_STATUS;
    readonly unread = input<readonly WorkflowSetting[]>([]);
    readonly rules = input<RulesView | null>(null);
    readonly prompts = input<readonly PromptView[]>([]);
    readonly funnel = input<FunnelView | null>(null);
    /**
     * The sub-node the sheet was opened from (ISC-406) — `knockout:<id>`, `score:<block>` or
     * `prompt:<id>` — or null. The sheet scrolls that section to the top of its own scroll box.
     */
    readonly section = input<string | null>(null);
    readonly closed = output();

    protected readonly headingId = `lg-stage-sheet-heading-${nextId++}`;
    private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');
    private readonly body = viewChild.required<ElementRef<HTMLElement>>('body');
    private readonly injector = inject(Injector);
    /** The stage and section last scrolled to, so data arriving later does not scroll the reader back. */
    private scrolledTo: string | null = null;
    private highlightTimer: ReturnType<typeof setTimeout> | null = null;

    constructor() {
        // Scroll the section into place once per stage and section. The rules and prompts may land
        // after the sheet, so every input that can add the section re-tries until it is drawn.
        effect(() => {
            const stage = this.stage();
            const section = this.section();
            this.rules();
            this.prompts();
            this.funnel();
            const key = section === null ? null : `${typeof stage === 'string' ? stage : stage.id}|${section}`;
            if (key === null || key === this.scrolledTo) {
                if (key === null) this.scrolledTo = null;
                return;
            }
            afterNextRender(() => this.scrollTo(key, section!), {injector: this.injector});
        });
        inject(DestroyRef).onDestroy(() => this.highlightTimer !== null && clearTimeout(this.highlightTimer));

        // Every new stage moves focus to the heading, not only the first: picking another node
        // while the sheet is open is a new selection too. After render, so the heading exists.
        effect(() => {
            this.stage();
            afterNextRender(() => this.heading().nativeElement.focus(), {injector: this.injector});
        });
    }

    /**
     * Puts the section flush with the top of `.sheet-body` by its `scrollTop`, never `scrollIntoView`,
     * which would scroll the page too; then marks it for a moment. Matched on the dataset, since an
     * id may carry characters a selector would need escaped.
     */
    private scrollTo(key: string, section: string): void {
        const body = this.body().nativeElement;
        const target = Array.from(body.querySelectorAll<HTMLElement>('[data-sub]')).find((el) => el.dataset['sub'] === section);
        if (target === undefined) return;
        this.scrolledTo = key;
        body.scrollTop += target.getBoundingClientRect().top - body.getBoundingClientRect().top;
        for (const marked of Array.from(body.querySelectorAll('[data-targeted]'))) marked.removeAttribute('data-targeted');
        target.setAttribute('data-targeted', '');
        if (this.highlightTimer !== null) clearTimeout(this.highlightTimer);
        this.highlightTimer = setTimeout(() => {
            target.removeAttribute('data-targeted');
            this.highlightTimer = null;
        }, HIGHLIGHT_MS);
    }

    protected close(): void {
        this.closed.emit();
    }
}
