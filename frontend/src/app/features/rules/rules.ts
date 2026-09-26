import {ChangeDetectionStrategy, Component, computed, DestroyRef, effect, ElementRef, inject, input, OnInit, signal, viewChild} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {injectDispatch} from '@ngrx/signals/events';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {WorkflowStage} from '@core/model/workflow';
import {injectTick} from '@core/time/tick';
import {configEvents} from '@core/store/config.events';
import {ConfigStore} from '@core/store/config.store';
import {IngestStore} from '@core/store/ingest.store';
import {ShortlistStore} from '@core/store/shortlist.store';
import {Icon} from '@shared/icon/icon';
import {PageHeader} from '@shared/page-header/page-header';
import {FlowCanvas} from './flow-canvas/flow-canvas';
import {FlowLegend} from './flow-legend/flow-legend';
import {StageSheet} from './stage-sheet/stage-sheet';
import {StageRail, UNREAD_STAGE} from './stage-rail/stage-rail';
import {stageCounts} from './stage-count';
import {fanIn} from './fan-in';
import {failedStageIds, markersOf} from './stage-marks';
import {RUN_STATUS, RunState, runState} from './run-state';
import {formatStartedAt} from './run-time';

/**
 * The content width, in px, below which the canvas gives way to the pipe (ISC-395): 44rem at the
 * default root size. Measured, not guessed — the fitted canvas scales its 184 px stage cards with
 * the box, about 0.143 px per px of width over this workflow: 207 px at 1440, 147 at 1024, 110 at
 * 768 and 100 at 700, the point where a card's label stops reading. Pixels rather than rem because
 * the fit that shrinks the cards works in pixels too.
 */
export const PIPE_BELOW_PX = 704;

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
 * (spec 017, ISC-393). The graph replaced 008's split view; below {@link PIPE_BELOW_PX} the stage
 * rail, drawn as a vertical pipe, replaces the graph (ISC-395).
 *
 * Replaces spec 007's anchor rail on this screen (spec 008, § Decisions): the stages are the
 * navigation, so a second list of in-page anchors would be two answers to one question.
 */
@Component({
    selector: 'lg-rules',
    imports: [FlowCanvas, FlowLegend, Icon, PageHeader, RouterLink, StageRail, StageSheet, TranslocoPipe],
    templateUrl: './rules.html',
    styleUrl: './rules.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: {
        '(document:keydown.escape)': 'escapeAnywhere($event)',
        '(document:pointerdown)': 'pressed($event)',
        '(document:click)': 'clickedAnywhere($event)',
        '(pointerover)': 'pointerOver($event)',
        '(pointerout)': 'pointerOut($event)',
        '(focusin)': 'focusIn($event)',
        '(focusout)': 'focusOut($event)',
    },
})
export class Rules implements OnInit {
    private readonly dispatch = injectDispatch(configEvents);
    protected readonly store = inject(ConfigStore);
    /**
     * `rulesOpened` asks for the last run and the funnel, and a store nobody has created does
     * not answer. The rail's counts are read from the last run alone (spec 008 § Decisions);
     * the funnel is what FILTER's detail pane draws (ISC-289). Protected, not private: the
     * run header (ISC-414) is fed `current()` and `running()` straight off this store from the
     * template — a direct read, not a second store and not a computed wrapping one.
     */
    protected readonly ingest = inject(IngestStore);
    private readonly shortlist = inject(ShortlistStore);
    /** The shared one-second clock (ISC-412), read for the running stage's elapsed seconds. */
    private readonly tick = injectTick();
    private readonly router = inject(Router);
    private readonly route = inject(ActivatedRoute);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly sheetRef = viewChild(StageSheet, {read: ElementRef<HTMLElement>});
    /** The open sheet's host, which the canvas pans the selected node out from under (ISC-394). */
    protected readonly sheetBox = computed((): HTMLElement | null => this.sheetRef()?.nativeElement ?? null);

    /** The id the unread entry carries, so the header's link and the rail's entry agree on it. */
    protected readonly unread = UNREAD_STAGE;

    /** The id the status control carries, matched by `selected` the way the unread one is. */
    protected readonly runStatus = RUN_STATUS;

    private readonly transloco = inject(TranslocoService);
    private readonly lang = toSignal(this.transloco.langChanges$, {initialValue: this.transloco.getActiveLang()});

    /**
     * Whether the run that finished last failed. It paints the status chip (operator, 2026-09-26,
     * overruling the design pass): a failed night is worth seeing before the graph is read, and
     * the chip is the one thing on this screen that always answers about a run. A pass in flight
     * outranks it — what is happening now is the more useful fact, and the failure is still in the
     * panel behind the chip.
     */
    protected readonly lastRunFailed = computed((): boolean => this.ingest.lastRun()?.status === 'FAILED');

    /** The last run's finish time as a time of day, for the status chip's trailing slot. */
    protected readonly finishedAtLabel = computed((): string | null => {
        const finishedAt = this.ingest.lastRun()?.finishedAt;
        return finishedAt === undefined ? null : formatStartedAt(finishedAt, this.lang());
    });

    /**
     * The status chip's accessible name, which says the state in words: the chip's own colour and
     * its trailing pill carry it visually, and neither reaches a screen reader.
     */
    protected readonly statusLabel = computed((): string => {
        const run = this.ingest.current();
        if (run !== null && run.stagePosition !== null && run.stageTotal !== null) {
            return this.transloco.translate('rules.status.ariaRunning', {position: run.stagePosition, total: run.stageTotal});
        }
        const finished = this.finishedAtLabel();
        if (finished === null) {
            return this.transloco.translate('rules.status.ariaNone');
        }
        const key = this.lastRunFailed() ? 'rules.status.ariaFailed' : 'rules.status.ariaFinished';
        return this.transloco.translate(key, {time: finished});
    });

    /**
     * The `stage` query parameter, bound by the router. The selection lives in the URL and
     * nowhere else, so a reload reads it back and the back button restores it (ISC-288): the
     * rail's entries are links that push one history entry each.
     *
     * The transform is not optional. Router input binding writes `undefined` for a parameter
     * absent from the URL rather than leaving the declared default.
     */
    readonly stage = input('', {transform: (value: string | undefined) => value ?? ''});

    /**
     * The `section` query parameter (ISC-406): the canvas sub-node the sheet was opened from, which
     * the sheet scrolls to. A second query parameter rather than a fragment — a fragment fights the
     * base href — and cleared with `stage` whenever the sheet closes or another stage is picked.
     */
    readonly section = input<string | null, string | undefined>(null, {transform: (value: string | undefined) => value ?? null});

    /**
     * Whether the box takes the whole window (ISC-407, refined 2026-09-26 on the operator's call).
     * Two sizes, both this screen's own CSS: the real full screen is gone, because hiding the
     * browser's tabs and address bar is the wrong trade for watching an eleven-minute pass, and
     * because the whole-window size needed no API to begin with.
     */
    protected readonly wholeWindow = signal(false);

    protected toggleWholeWindow(): void {
        this.wholeWindow.update((on) => !on);
    }

    /** The screen's own content width as the ResizeObserver last reported it; null before the first report. */
    private readonly boxWidth = signal<number | null>(null);

    /**
     * Canvas or pipe (ISC-395) — a container query on this screen's box, done with a ResizeObserver
     * rather than CSS because the narrow side must not instantiate vflow at all, and `display: none`
     * would. Until the first report — and without an observer at all — the pipe is drawn, so a
     * 320 px screen never builds the canvas even for a frame.
     */
    protected readonly wide = computed((): boolean => {
        const width = this.boxWidth();
        return width !== null && width >= PIPE_BELOW_PX;
    });

    /** The fan-in the canvas draws, said in words beside it; the pipe says its own. */
    protected readonly fanIn = computed(() => fanIn(this.store.workflow()));

    /**
     * The stage the heartbeat reports, as a plain string, and whether a pass is reported at all.
     * Both derived rather than read off `current()` directly: the store hands out a fresh object
     * on every poll, so a reader of the run itself would fire twelve times a minute where nothing
     * changed, and the live region would repeat itself (ISC-416).
     */
    private readonly reportedStage = computed((): string | null => this.ingest.current()?.stage ?? null);
    private readonly passReported = computed((): boolean => this.ingest.current() !== null);

    /** The last run's finish time, as one string: what the reload after a pass has to differ from. */
    private readonly finishedAt = computed((): string | null => this.ingest.lastRun()?.finishedAt ?? null);

    /** The stage the region has already named, and whether the previous answer carried a pass. */
    private announcedStage: string | null = null;
    private passWasReported = false;

    /** The encoding the pass had while it was still reported, and what `lastRun` said back then. */
    private liveRun: RunState | null = null;
    private finishedBeforeRun: string | null = null;

    /**
     * That encoding, held after the pass is gone from the heartbeat (ISC-418). The heartbeat drops
     * the run the moment it ends, while `lastRun` still holds the run *before* it for as long as
     * the reload `RefreshStore` asks for takes — a graph reading only the heartbeat would show the
     * previous run's chips for that moment, which reads as the pass having produced them.
     */
    private readonly heldRun = signal<RunState | null>(null);

    /**
     * The one sentence the polite live region holds (ISC-416): which stage the pass has reached,
     * and that it has ended. A catalog key with its parameters rather than finished text, so the
     * sentence is translated in the template the way every other sentence on this screen is.
     */
    protected readonly announcement = signal<{key: string; params: Record<string, string>} | null>(null);

    constructor() {
        /*
         * The stage change and the end of the pass, said once each. An effect rather than a
         * computed because "has ended" is a transition and not a state: with the run gone there is
         * nothing left to derive it from. No timer of its own — it answers the store's heartbeat,
         * which is the only clock on this screen (ISC-419).
         */
        effect(() => {
            const stage = this.reportedStage();
            const reported = this.passReported();
            const finishedAt = this.finishedAt();
            const wasReported = this.passWasReported;
            this.passWasReported = reported;

            if (reported) {
                if (stage !== null && stage !== this.announcedStage) {
                    this.announcement.set({key: 'rules.run.reached', params: {stage}});
                    this.announcedStage = stage;
                }
                this.liveRun = this.runState();
                this.finishedBeforeRun = finishedAt;
                this.heldRun.set(null);
                return;
            }

            if (wasReported) {
                this.announcement.set({key: 'rules.run.ended', params: {}});
                this.announcedStage = null;
                // ISC-418: hold what the pass left. The release is judged on the next answer,
                // never on this one — the reload cannot have arrived before the pass ended.
                this.heldRun.set(this.liveRun);
                return;
            }

            // The reload that follows a pass, recognised by a finish time that is not the one this
            // run started against; `lastRunFailed` clears the run, and a null is the same signal —
            // a graph waiting for a reload that never comes would show a finished pass as running.
            if (finishedAt === null || finishedAt !== this.finishedBeforeRun) {
                this.heldRun.set(null);
            }
        });

        if (typeof ResizeObserver === 'undefined') {
            return;
        }
        const observer = new ResizeObserver((entries) => {
            const width = entries.at(-1)?.contentRect.width;
            if (width !== undefined) {
                this.boxWidth.set(width);
            }
        });
        observer.observe(this.host.nativeElement);
        inject(DestroyRef).onDestroy(() => observer.disconnect());
    }

    private readonly stages = computed(
        (): readonly WorkflowStage[] => this.store.workflow()?.phases.flatMap((phase) => phase.stages) ?? [],
    );

    /**
     * The id the URL names, matched without regard to case so a hand-typed `?stage=filter`
     * finds `FILTER`; `unread` for the keys nothing reads, `run` for the run status; nothing for
     * none and for a value the server did not name, and then no sheet opens (spec 017 settles fog
     * 3: the graph is the page, a stage opens on demand — 008 selected the first stage instead). An
     * unknown value is left standing in the URL rather than rewritten, and the next click replaces it.
     */
    protected readonly selected = computed((): string | null => {
        const wanted = this.stage().toLowerCase();
        if (wanted === UNREAD_STAGE || wanted === RUN_STATUS) {
            return wanted;
        }
        return this.stages().find((stage) => stage.id.toLowerCase() === wanted)?.id ?? null;
    });

    /** What the sheet shows: the selected stage itself, the unread entry, or the run status. */
    protected readonly detail = computed((): WorkflowStage | typeof UNREAD_STAGE | typeof RUN_STATUS | null => {
        const selected = this.selected();
        if (selected === UNREAD_STAGE || selected === RUN_STATUS) {
            return selected;
        }
        return this.stages().find((stage) => stage.id === selected) ?? null;
    });

    /** What the last run left at each stage; an absent count is shown as none, never as a zero. */
    protected readonly counts = computed(() => stageCounts(this.store.workflow(), this.ingest.lastRun()));

    /** The last run as the rail reads it: the run-less line, and which stage failed. */
    protected readonly lastRun = computed(() => this.ingest.lastRun());

    /**
     * A readable name per knockout, keyed by its id: `/api/v1/offers/funnel` already formats them
     * ("Beyond reach, not remote"), and the run's own `removed` map is keyed by the server's
     * SCREAMING_SNAKE stage names. **Only the words are borrowed** — the funnel's figures are the
     * standing working list and the run status's are this run's, and two funnels on one screen
     * that disagree is exactly the failure to avoid.
     */
    protected readonly removalLabels = computed((): ReadonlyMap<string, string> =>
        new Map((this.funnel()?.stages ?? []).map((stage) => [stage.id, stage.label])),
    );

    /** The six filter stages' removals, drawn on FILTER's detail pane. */
    protected readonly funnel = computed(() => this.shortlist.funnel());

    /**
     * Where the current pass stands against the workflow (ISC-410.2): null while the workflow has
     * not loaded, since {@link runState} needs the stage order to place a run against. Recomputed
     * whenever the workflow or the current run changes.
     */
    protected readonly runState = computed((): RunState | null => {
        const workflow = this.store.workflow();
        return workflow === null ? null : runState(workflow, this.ingest.current());
    });

    /**
     * What the graph and the pipe draw (ISC-418): the pass the heartbeat reports, else the
     * encoding held until the reloaded last run has arrived, else the run-less state, in which no
     * node carries any of the three and every chip is back.
     */
    protected readonly shownRun = computed((): RunState | null => {
        const live = this.runState();
        if (live !== null && live.running !== null) {
            return live;
        }
        return this.heldRun() ?? live;
    });

    /**
     * How long the in-flight stage has been running, in whole seconds (ISC-410.2): null with no
     * pass running or before that pass has entered a stage. `injectTick()` answers epoch
     * milliseconds, so the subtraction against `stageStartedAt` is this consumer's, as documented
     * on {@link injectTick}.
     */
    protected readonly elapsedSeconds = computed((): number | null => {
        const current = this.ingest.current();
        if (current === null || current.stageStartedAt === null) {
            return null;
        }
        return Math.floor((this.tick() - Date.parse(current.stageStartedAt)) / 1000);
    });

    /**
     * The stage under the pointer and the stage holding keyboard focus (ISC-405). View state, not
     * in the URL: it answers where the reader is looking, which no reload should restore. Two
     * signals so a pointer leaving a node does not clear the answer to the focused one.
     */
    private readonly hoveredStage = signal<string | null>(null);
    private readonly focusedStage = signal<string | null>(null);

    /**
     * The legend entry under the pointer (operator, 2026-09-26), which lights the stages that carry
     * it. Pointer only, deliberately: the entries are not controls and putting fourteen of them
     * into the tab order would cost more than it gives — a screen reader already hears every
     * marker on the node itself, which is the direction this one reverses.
     */
    private readonly hoveredMarker = signal<string | null>(null);

    /**
     * The stages that carry the hovered marker; null with no entry under the pointer, and then no
     * node is lit and none is dimmed. Read through `markersOf`, the same function the legend's own
     * highlight uses, so the two directions can never disagree about what a stage carries.
     */
    protected readonly litStages = computed((): ReadonlySet<string> | null => {
        const marker = this.hoveredMarker();
        if (marker === null) {
            return null;
        }
        const failed = failedStageIds(this.lastRun());
        return new Set(this.stages().filter((stage) => markersOf(stage, failed.has(stage.id)).has(marker)).map((stage) => stage.id));
    });

    /**
     * The markers the legend highlights: those the hovered (else focused) stage carries, read by
     * `markersOf`, the function built from the node's own predicates. Null leaves the legend as is.
     */
    protected readonly legendActive = computed((): ReadonlySet<string> | null => {
        const id = this.hoveredStage() ?? this.focusedStage();
        const stage = id === null ? undefined : this.stages().find((s) => s.id === id);
        return stage === undefined ? null : markersOf(stage, failedStageIds(this.lastRun()).has(stage.id));
    });

    /**
     * The canvas node or pipe pill an event came from. Delegated here rather than emitted by each
     * node, because `pointerover` and `focusin` bubble — one listener covers both drawings, and
     * the pipe below the breakpoint answers without a change to the rail.
     */
    private stageAt(target: EventTarget | null): string | null {
        if (!(target instanceof Element)) {
            return null;
        }
        return target.closest<HTMLElement>('lg-flow-node [data-stage], lg-stage-rail [data-stage]')?.dataset['stage'] ?? null;
    }

    /** The legend entry an event came from, by the id the legend puts on every entry. */
    private markerAt(target: EventTarget | null): string | null {
        if (!(target instanceof Element)) {
            return null;
        }
        return target.closest<HTMLElement>('lg-flow-legend [data-marker-id]')?.dataset['markerId'] ?? null;
    }

    protected pointerOver(event: Event): void {
        this.hoveredStage.set(this.stageAt(event.target));
        this.hoveredMarker.set(this.markerAt(event.target));
    }

    /** Only a pointer that left for somewhere outside every node clears; one moving inside a node does not. */
    protected pointerOut(event: MouseEvent): void {
        if (this.stageAt(event.relatedTarget) === null) {
            this.hoveredStage.set(null);
        }
        if (this.markerAt(event.relatedTarget) === null) {
            this.hoveredMarker.set(null);
        }
    }

    protected focusIn(event: FocusEvent): void {
        this.focusedStage.set(this.stageAt(event.target));
    }

    protected focusOut(event: FocusEvent): void {
        this.focusedStage.set(this.stageAt(event.relatedTarget));
    }

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
        // The wrapper before the sheet, the order the browser keeps for its own full screen: an
        // Escape out of the whole-window size is about the size, and the sheet stays where it was.
        if (this.wholeWindow()) {
            this.wholeWindow.set(false);
            return;
        }
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
     * link beside the ruleset badge, or the rail's entry for a stage neither of them draws.
     */
    protected async closeSheet(): Promise<void> {
        const stage = this.selected();
        await this.router.navigate([], {relativeTo: this.route, queryParams: {stage: null, section: null}, queryParamsHandling: 'merge'});
        if (stage === null) {
            return;
        }
        // Matched on the dataset rather than a selector: an ingest id carries a space, and jsdom
        // has no `CSS.escape`.
        const link = (scope: string): HTMLElement | undefined =>
            Array.from(this.host.nativeElement.querySelectorAll<HTMLElement>(`${scope} a[data-stage]`)).find(
                (a) => a.dataset['stage'] === stage,
            );
        const origin = link('lg-flow-canvas') ?? link('lg-page-header') ?? link('lg-stage-rail');
        // Without a scroll: the canvas card sits in an `overflow: hidden` box, which focus would scroll.
        origin?.focus({preventScroll: true});
    }
}

