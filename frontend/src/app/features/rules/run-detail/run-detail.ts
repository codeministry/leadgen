import {ChangeDetectionStrategy, Component, computed, inject, input} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {RouterLink} from '@angular/router';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {CurrentRunView} from '@core/model/current-run';
import {FunnelStageCount} from '@core/model/funnel';
import {LastRunStage, LastRunView} from '@core/model/last-run';
import {AgoPipe} from '@shared/date/ago.pipe';
import {Badge, BadgeTone} from '@shared/badge/badge';
import {EmptyState} from '@shared/empty-state/empty-state';
import {FunnelRail} from '@shared/funnel-rail/funnel-rail';
import {Icon} from '@shared/icon/icon';
import {formatStartedAt} from '../run-time';

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

    /** The run's own eight figures, in the order a run produces them. */
    protected readonly figures = computed((): readonly {key: string; value: number}[] => {
        const run = this.lastRun();
        if (run === null) {
            return [];
        }
        return [
            {key: 'extractedLabel', value: run.extracted},
            {key: 'writtenLabel', value: run.written},
            {key: 'mergedLabel', value: run.merged},
            {key: 'enrichedLabel', value: run.enriched},
            {key: 'scoredLabel', value: run.scored},
            {key: 'shortlistedLabel', value: run.shortlisted},
            {key: 'reviewLabel', value: run.review},
            {key: 'packagedLabel', value: run.packaged},
        ];
    });

    /** The slowest recorded stage, marked in the timing table; null when nothing was recorded. */
    protected readonly slowest = computed((): number | null => {
        const stages = this.lastRun()?.stages ?? [];
        if (stages.length === 0) {
            return null;
        }
        return stages.reduce((slow, stage) => (stage.millis > slow.millis ? stage : slow), stages[0]!).position;
    });

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
