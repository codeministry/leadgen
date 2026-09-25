import {ChangeDetectionStrategy, Component, computed, ElementRef, inject, input, OnInit, viewChild} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
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
import {FlowLegend} from './flow-legend/flow-legend';
import {StageSheet} from './stage-sheet/stage-sheet';
import {StageRail, UNREAD_STAGE} from './stage-rail/stage-rail';
import {stageCounts} from './stage-count';

/** How far a pointer may travel between down and up and still count as a click rather than a pan. */
const PAN_SLOP_PX = 4;

/** An open `<dialog>` or popover, which owns the Escape key while it is up. */
function otherOverlayOpen(): boolean {
    if (document.querySelector('dialog[open]') !== null) {
        return true;
    }
    try {
        return document.querySelector(':popover-open') !== null;
    } catch {
        // jsdom does not know the pseudo-class and throws on the selector.
        return false;
    }
}

/**
 * The pipeline as a flow graph, the selected stage opened in a sheet at the right edge of the window
 * (spec 017, ISC-393). The graph replaced 008's split view; the stage rail stays below the canvas
 * until it becomes the pipe.
 *
 * Replaces spec 007's anchor rail on this screen (spec 008, § Decisions): the stages are the
 * navigation, so a second list of in-page anchors would be two answers to one question.
 */
@Component({
    selector: 'lg-rules',
    imports: [Badge, FlowCanvas, FlowLegend, PageHeader, StageRail, StageSheet, TranslocoPipe],
    templateUrl: './rules.html',
    styleUrl: './rules.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: {
        '(document:keydown.escape)': 'escapeAnywhere($event)',
        '(document:pointerdown)': 'pressed($event)',
        '(document:click)': 'clickedAnywhere($event)',
    },
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
    private readonly router = inject(Router);
    private readonly route = inject(ActivatedRoute);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly sheetRef = viewChild(StageSheet, {read: ElementRef<HTMLElement>});
    /** The open sheet's host, which the canvas pans the selected node out from under (ISC-394). */
    protected readonly sheetBox = computed((): HTMLElement | null => this.sheetRef()?.nativeElement ?? null);

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
     * finds `FILTER`; `unread` for the keys nothing reads; nothing for none and for a value the
     * server did not name, and then no sheet opens (spec 017 settles fog 3: the graph is the page,
     * a stage opens on demand — 008 selected the first stage instead). An unknown value is left
     * standing in the URL rather than rewritten, and the next click replaces it.
     */
    protected readonly selected = computed((): string | null => {
        const wanted = this.stage().toLowerCase();
        if (wanted === UNREAD_STAGE) {
            return UNREAD_STAGE;
        }
        return this.stages().find((stage) => stage.id.toLowerCase() === wanted)?.id ?? null;
    });

    /** What the sheet shows: the selected stage itself, or the unread entry. */
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

    /** Where the last pointer went down, so a click that ended a pan is told from a plain click. */
    private press: {x: number; y: number; inSheet: boolean} | null = null;

    /**
     * Escape anywhere on the screen closes the sheet the way its close button does (ISC-403). The
     * sheet handles an Escape from inside itself, so that one is left to it — two navigations for
     * one key would push two history entries. An open dialog or popover owns its Escape.
     */
    protected escapeAnywhere(event: Event): void {
        if (this.detail() === null || event.defaultPrevented || this.inSheet(event.target) || otherOverlayOpen()) {
            return;
        }
        void this.closeSheet();
    }

    protected pressed(event: PointerEvent): void {
        this.press = {x: event.clientX, y: event.clientY, inSheet: this.inSheet(event.target)};
    }

    /**
     * A click outside the sheet closes it (ISC-403), except where the click already does something:
     * a link selects or navigates (the router prevents the default of the links it follows), a
     * canvas control zooms or expands, and a pointer that moved more than a few pixels panned the
     * canvas. A click that started inside the sheet, a text selection dragged out of it, stays too.
     */
    protected clickedAnywhere(event: MouseEvent): void {
        const press = this.press;
        this.press = null;
        if (this.detail() === null || event.defaultPrevented || this.inSheet(event.target) || press?.inSheet) {
            return;
        }
        if (event.target instanceof Element && event.target.closest('a[href], button, [data-action], dialog, [popover]') !== null) {
            return;
        }
        if (press !== null && Math.hypot(event.clientX - press.x, event.clientY - press.y) > PAN_SLOP_PX) {
            return;
        }
        void this.closeSheet();
    }

    private inSheet(target: EventTarget | null): boolean {
        const sheet = this.sheetBox();
        return sheet !== null && target instanceof Node && sheet.contains(target);
    }

    /**
     * Closing the sheet removes `stage` from the URL (one history entry, so back reopens it) and
     * hands focus back to the node that opened it — the canvas card, or for the unread keys the
     * legend's entry, or the rail's entry for a stage neither of them draws.
     */
    protected async closeSheet(): Promise<void> {
        const stage = this.selected();
        await this.router.navigate([], {relativeTo: this.route, queryParams: {stage: null}, queryParamsHandling: 'merge'});
        if (stage === null) {
            return;
        }
        // Matched on the dataset rather than a selector: an ingest id carries a space, and jsdom
        // has no `CSS.escape`.
        const link = (scope: string): HTMLElement | undefined =>
            Array.from(this.host.nativeElement.querySelectorAll<HTMLElement>(`${scope} a[data-stage]`)).find(
                (a) => a.dataset['stage'] === stage,
            );
        const origin = link('lg-flow-canvas') ?? link('lg-flow-legend') ?? link('lg-stage-rail');
        // Without a scroll: the canvas card sits in an `overflow: hidden` box, which focus would scroll.
        origin?.focus({preventScroll: true});
    }
}
