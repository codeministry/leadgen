import {ChangeDetectionStrategy, Component, computed, inject, input} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {RouterLink} from '@angular/router';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {CurrentRunView} from '@core/model/current-run';
import {FunnelStageCount} from '@core/model/funnel';
import {LastRunStage, LastRunView} from '@core/model/last-run';
import {LlmBudgetView} from '@core/model/llm-budget';
import {AgoPipe} from '@shared/date/ago.pipe';
import {Badge, BadgeTone} from '@shared/badge/badge';
import {EmptyState} from '@shared/empty-state/empty-state';
import {FunnelRail} from '@shared/funnel-rail/funnel-rail';
import {Icon} from '@shared/icon/icon';
import {RunState, StageRunState} from '../run-state';
import {formatFinishedAt, formatStartedAt} from '../run-time';
import {DONE_ICON, PENDING_ICON, RUNNING_ICON} from '../stage-marks';

/**
 * Whole seconds between two instants, or null when either is missing or they run backwards — a
 * negative duration is a clock problem, not a fact about the run.
 */
function secondsBetween(from: string | null, to: string | null): number | null {
    if (from === null || to === null) {
        return null;
    }
    const seconds = Math.round((Date.parse(to) - Date.parse(from)) / 1000);
    return Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
}

/** One stage of the pass in flight, as the list shows it. */
export interface RunStep {
    readonly stage: string;
    readonly state: StageRunState;
    /** What the same stage took in the run before, in ms; null when that run did not time it. */
    readonly previousMillis: number | null;
}

/**
 * The run status the header's chip opens (operator, 2026-09-26): the pass in flight if there is
 * one, else the run that finished last, with every fact either payload carries.
 *
 * <p>Fed entirely through inputs, like `lg-flow-node` — it injects no store.
 * The screen owns which run this is and hands it in, so the panel cannot disagree with the graph
 * beside it about what is running.
 *
 * <p><b>While a pass runs, the last run's counts are not shown.</b> That is the whole reason
 * `CurrentRunView` is its own type rather than a `LastRunView` with a flag: a running row carries
 * zeros, and under a "running" head they would claim a pass that found nothing. In their place one
 * line names when the previous run finished, so the reader knows which run the numbers will belong
 * to when they come back.
 */
@Component({
    selector: 'lg-run-detail',
    imports: [AgoPipe, Badge, EmptyState, FunnelRail, Icon, RouterLink, TranslocoPipe],
    templateUrl: './run-detail.html',
    styleUrl: './run-detail.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunDetail {
    /** The pass in flight, or null. */
    readonly current = input<CurrentRunView | null>(null);
    /** The run that finished last, or null when none ever has. */
    readonly lastRun = input<LastRunView | null>(null);
    /** Seconds spent in the running stage, from the screen's shared clock. */
    readonly elapsed = input<number | null>(null);
    /**
     * A readable name per knockout id, handed in by the screen. The run's `removed` map is keyed by
     * the server's stage names (`NO_CORE_SKILL`); these are the same criteria with the words a
     * person reads. A key with no name falls back to the server's own, which is what the graph's
     * sub-nodes show too — never an empty label.
     */
    readonly removalLabels = input<ReadonlyMap<string, string>>(new Map());
    /**
     * Where the pass in flight stands against the workflow: the screen's own `runState`, the one
     * the graph beside this panel draws, so the list and the graph cannot disagree about which
     * stage is running. Joined by stage name, never by position — see `runState`.
     */
    readonly runState = input<RunState | null>(null);
    /** Seconds since the pass in flight started, from the screen's shared clock. */
    readonly runElapsed = input<number | null>(null);
    /** Today's model calls against the ceiling; null until read, or when the read failed. */
    readonly budget = input<LlmBudgetView | null>(null);

    /**
     * Whether the day's allowance is spent — the one budget state worth a badge, because it is
     * why a pass leaves scoring undone. No ceiling is never spent.
     */
    protected readonly budgetSpent = computed((): boolean => {
        const budget = this.budget();
        return budget !== null && budget.limit !== null && budget.used >= budget.limit;
    });

    protected readonly doneIcon = DONE_ICON;
    protected readonly runningIcon = RUNNING_ICON;
    protected readonly pendingIcon = PENDING_ICON;

    private readonly transloco = inject(TranslocoService);
    private readonly lang = toSignal(this.transloco.langChanges$, {initialValue: this.transloco.getActiveLang()});

    /** Nothing has ever run and nothing is running: the panel says so rather than showing empties. */
    protected readonly empty = computed((): boolean => this.current() === null && this.lastRun() === null);

    /** The time of day a live pass started, never a calendar distance — see `formatStartedAt`. */
    protected readonly startedAt = computed((): string | null => {
        const run = this.current();
        return run === null ? null : formatStartedAt(run.startedAt, this.lang());
    });

    /** The judge, from whichever payload is being shown. */
    protected readonly scoreModel = computed((): string | null => this.current()?.scoreModel ?? this.lastRun()?.scoreModel ?? null);

    /**
     * One badge tone per recorded status, from a literal map: a class assembled at runtime is a
     * class Tailwind never emits, which this repo has measured once already.
     */
    protected readonly statusTone = computed((): BadgeTone => {
        const status = this.lastRun()?.status ?? '';
        if (status === 'FAILED') return 'error';
        if (status === 'AWAITING_BATCH') return 'accent';
        return status === 'COMPLETE' ? 'success' : 'neutral';
    });

    /**
     * The hard filter's removals as the shared rail reads them. `NO_CORE_SKILL` is `no-core-skill`
     * on every other surface, so the key is folded before the name is looked up.
     */
    protected readonly removed = computed((): readonly FunnelStageCount[] => {
        const labels = this.removalLabels();
        return Object.entries(this.lastRun()?.removed ?? {}).map(([id, count]) => {
            const key = id.toLowerCase().replaceAll('_', '-');
            return {id: key, label: labels.get(key) ?? id, removed: count};
        });
    });

    /**
     * The run's own eight figures, in the order a run produces them, each with its change against
     * the run before — null without one. The delta is neutral on purpose: more offers read is
     * neither good nor bad, so it is a number beside a number and never a colour.
     */
    protected readonly figures = computed((): readonly {key: string; value: number; delta: number | null}[] => {
        const run = this.lastRun();
        if (run === null) {
            return [];
        }
        const before = run.previous;
        const figure = (key: string, value: number, previous: number | undefined) => ({
            key,
            value,
            delta: previous === undefined ? null : value - previous,
        });
        return [
            figure('extractedLabel', run.extracted, before?.extracted),
            figure('writtenLabel', run.written, before?.written),
            figure('mergedLabel', run.merged, before?.merged),
            figure('enrichedLabel', run.enriched, before?.enriched),
            figure('scoredLabel', run.scored, before?.scored),
            figure('shortlistedLabel', run.shortlisted, before?.shortlisted),
            figure('reviewLabel', run.review, before?.review),
            figure('packagedLabel', run.packaged, before?.packaged),
        ];
    });

    /** When the last run finished, placed in the day — see `formatFinishedAt`. */
    protected readonly finishedAt = computed((): string | null => {
        const run = this.lastRun();
        return run === null ? null : formatFinishedAt(run.finishedAt, this.lang());
    });

    /** How long the last run took as a whole, in seconds; null for a run without its start. */
    protected readonly took = computed((): number | null => {
        const run = this.lastRun();
        return run === null ? null : secondsBetween(run.startedAt, run.finishedAt);
    });

    /** The change in duration against the run before, in seconds; null without one. */
    protected readonly tookDelta = computed((): number | null => {
        const took = this.took();
        const before = this.lastRun()?.previous ?? null;
        const tookBefore = before === null ? null : secondsBetween(before.startedAt, before.finishedAt);
        return took === null || tookBefore === null ? null : took - tookBefore;
    });

    /** The slowest recorded stage, marked in the timing table; null when nothing was recorded. */
    protected readonly slowest = computed((): number | null => {
        const stages = this.lastRun()?.stages ?? [];
        if (stages.length === 0) {
            return null;
        }
        return stages.reduce((slow, stage) => (stage.millis > slow.millis ? stage : slow), stages[0]!).position;
    });

    /**
     * What each stage took in the run that finished last, by stage name. The only timings there
     * are while a pass runs: the pass's own are written when it ends, so the list estimates from
     * these and says so.
     */
    private readonly previousMillis = computed((): ReadonlyMap<string, number> =>
        new Map((this.lastRun()?.stages ?? []).map((stage) => [stage.stage, stage.millis])),
    );

    /**
     * Every stage of the pass in flight, in run order, with where it stands. Empty with no pass,
     * and empty while the pass has not yet entered a stage the workflow draws — then the head's
     * "step n of m" is all there is to say.
     */
    protected readonly steps = computed((): readonly RunStep[] => {
        const state = this.runState();
        if (this.current() === null || state === null || state.running === null) {
            return [];
        }
        const previous = this.previousMillis();
        return state.order.map((stage) => ({
            stage,
            state: state.states.get(stage) ?? 'pending',
            previousMillis: previous.get(stage) ?? null,
        }));
    });

    protected readonly stepsDone = computed((): number => this.steps().filter((step) => step.state === 'done').length);

    /**
     * About how long the pass still needs, in seconds, going by the run before: what the stages
     * not reached yet took then, plus what is left of the running stage's previous time. Null
     * without a previous run's timings — an estimate from nothing is a number nobody should read.
     * A running stage already past its previous time contributes nothing rather than a negative.
     */
    protected readonly remaining = computed((): number | null => {
        const steps = this.steps();
        if (steps.length === 0 || this.previousMillis().size === 0) {
            return null;
        }
        const elapsedInStage = (this.elapsed() ?? 0) * 1000;
        const millis = steps.reduce((sum, step) => {
            if (step.previousMillis === null) return sum;
            if (step.state === 'pending') return sum + step.previousMillis;
            if (step.state === 'running') return sum + Math.max(0, step.previousMillis - elapsedInStage);
            return sum;
        }, 0);
        return Math.round(millis / 1000);
    });

    /** Whole seconds as `m:ss`, or `n s` under a minute: the grain a person reads a wait in. */
    protected clock(seconds: number): string {
        if (seconds < 60) {
            return `${seconds} s`;
        }
        const minutes = Math.floor(seconds / 60);
        return `${minutes}:${String(seconds % 60).padStart(2, '0')}`;
    }

    /**
     * A previous run's duration in the same grain as {@link clock}. Under a second it says so
     * rather than rounding to "0 s": most stages take milliseconds, and "~0 s" in a column of
     * them read as stages that did nothing.
     */
    protected clockOf(millis: number): string {
        return millis < 1000 ? '<1 s' : this.clock(Math.round(millis / 1000));
    }

    /** A change with its sign, `+3` or `-40`, in the reader's number format; zero unsigned. */
    protected signed(delta: number): string {
        return new Intl.NumberFormat(this.lang(), {signDisplay: 'exceptZero'}).format(delta);
    }

    /** A change in duration with its sign, in the grain of {@link clock}. */
    protected signedClock(seconds: number): string {
        const sign = seconds > 0 ? '+' : seconds < 0 ? '-' : '';
        return sign + this.clock(Math.abs(seconds));
    }

    /** A recorded duration as seconds with one decimal, or milliseconds below a second. */
    protected duration(millis: number): string {
        return millis < 1000 ? `${millis} ms` : `${(millis / 1000).toFixed(1)} s`;
    }

    /**
     * The badge says what the stage's status was and nothing else. The slowest stage is marked on
     * its duration instead: a warning-toned "OK" reads as a warning about the state, which is the
     * one thing it is not — seen on screen and moved (2026-09-26).
     */
    protected stageTone(stage: LastRunStage): BadgeTone {
        return stage.status === 'FAILED' ? 'error' : 'success';
    }

    protected format(value: number): string {
        return new Intl.NumberFormat(this.lang()).format(value);
    }
}
