import {ChangeDetectionStrategy, Component, computed, inject, OnInit} from '@angular/core';
import {injectDispatch} from '@ngrx/signals/events';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {applicationEvents} from '@core/store/applications.events';
import {shortlistEvents} from '@core/store/shortlist.events';
import {ShortlistStore} from '@core/store/shortlist.store';
import {ApplicationsStore} from '@core/store/applications.store';
import {IngestStore} from '@core/store/ingest.store';
import {LastRunStage} from '@core/model/last-run';
import {Badge} from '@shared/badge/badge';
import {EmptyState} from '@shared/empty-state/empty-state';
import {FunnelRail} from '@shared/funnel-rail/funnel-rail';
import {Icon} from '@shared/icon/icon';
import {PageHeader} from '@shared/page-header/page-header';
import {StatTile} from '@shared/stat-tile/stat-tile';

/**
 * One row of the run table, from either kind of run.
 *
 * `warnings` is what the two kinds cannot share: a run this browser started names the
 * document that came up short, a recorded one can only name the source. Both are the same
 * shape so the template renders one table rather than two.
 */
interface DashboardRunSource {
    readonly sourceId: string;
    readonly documents: number;
    readonly extracted: number;
    readonly written: number;
    readonly warnings: readonly {
        readonly documentId: string | null;
        readonly extracted: number;
        readonly announced: number;
    }[];
}

@Component({
    selector: 'lg-dashboard',
    imports: [Badge, EmptyState, FunnelRail, Icon, PageHeader, StatTile, TranslocoPipe],
    templateUrl: './dashboard.html',
    styleUrl: './dashboard.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Dashboard implements OnInit {
    private readonly dispatch = injectDispatch(applicationEvents);
    private readonly shortlistDispatch = injectDispatch(shortlistEvents);
    private readonly transloco = inject(TranslocoService);
    protected readonly ingest = inject(IngestStore);
    protected readonly applications = inject(ApplicationsStore);
    protected readonly shortlist = inject(ShortlistStore);

    /**
     * The run this screen is talking about: the one this browser started if there is one,
     * otherwise the one the database remembers.
     *
     * <p>In that order and not the other way round. A run somebody just triggered is the
     * answer to "what did that do", and it carries the per-document detail a recorded run
     * cannot. The recorded one is what makes the screen useful on the far more common
     * morning where the pass ran while nobody was watching.
     */
    protected readonly runSources = computed<DashboardRunSource[]>(() => {
        const report = this.ingest.report();
        if (report !== null) {
            return report.sources.map((source) => ({
                sourceId: source.sourceId,
                documents: source.documents,
                extracted: source.extracted,
                written: source.written,
                // Per document, because a run this browser watched knows which document came up
                // short — which is the actionable half: a selector stops matching on one layout,
                // not on a whole source.
                warnings: source.details
                    .filter((document) => document.announced !== null && !document.complete)
                    .map((document) => ({
                        documentId: document.documentId,
                        extracted: document.extracted,
                        announced: document.announced as number,
                    })),
            }));
        }
        return (this.ingest.lastRun()?.sources ?? []).map((source) => ({
            sourceId: source.sourceId,
            documents: source.documents,
            extracted: source.extracted,
            written: source.written,
            // Per source, and that is the whole difference: `source_run` holds one row per
            // source per run, so the document that came up short is not recorded anywhere.
            warnings:
                source.announced !== null && !source.complete
                    ? [{documentId: null, extracted: source.extracted, announced: source.announced}]
                    : [],
        }));
    });

    protected readonly hasRun = computed(
        () => this.ingest.report() !== null || this.ingest.lastRun() !== null,
    );

    /**
     * How many offers this run actually sent to a judge, and which judge answered.
     *
     * <p>The per-run count, not the standing shortlist: a run judges what is stale, so zero
     * is the normal outcome of a pass that found nothing new. The catalog says that in words
     * rather than leaving a bare 0 to be read as scoring having stopped working, which is the
     * same reading `merged` had to be protected from.
     *
     * <p>The model comes from the recorded row alone, because an `IngestReport` does not
     * carry one. A run this browser started therefore shows the count without a scale until
     * the recorded row catches up, which is honest: two runs under two models are not
     * comparable, and guessing which one answered would hide exactly that.
     */
    protected readonly analysed = computed<{ readonly count: number; readonly model: string | null } | null>(() => {
        const report = this.ingest.report();
        const recorded = this.ingest.lastRun();
        if (report !== null) {
            return {count: report.scored.scored, model: recorded?.scoreModel ?? null};
        }
        return recorded === null ? null : {count: recorded.scored, model: recorded.scoreModel};
    });

    /**
     * How many things came up short of what they announced, over whichever run is on screen.
     *
     * <p>Counted from the rows rather than from `ingest.mismatches()`, which only ever sees a
     * run this browser started. A recorded run that lost offers to a selector must say so
     * just as loudly — that is the whole point of the check, and a nightly pass is exactly
     * where nobody is watching.
     */
    protected readonly mismatches = computed(() =>
        this.runSources().reduce((sum, source) => sum + source.warnings.length, 0),
    );

    protected readonly runExtracted = computed<number | null>(
        () => this.ingest.report()?.extracted ?? this.ingest.lastRun()?.extracted ?? null,
    );

    protected readonly runWritten = computed<number | null>(
        () => this.ingest.report()?.written ?? this.ingest.lastRun()?.written ?? null,
    );

    /**
     * When the run on screen finished, on the reader's own clock — whichever run it is.
     *
     * <p>It used to be filled only for a recorded run, on the reasoning that somebody who had
     * just clicked the button watched it happen. That holds for the minute after the click and
     * not afterwards: a pass over the standing backlog runs for hours, the tab stays open, and
     * the panel then said nothing at all about when the numbers on it were true.
     *
     * <p>`finishedAt` is an ISO instant in UTC, and slicing the string would show an 08:47 run
     * as 06:47 — the same trap the runs panel documents. The locale follows the chosen
     * language, because 09/02 and 02.09. are the same day written for two readers.
     */
    protected readonly finishedAt = computed<string | null>(() => {
        const finishedAt = this.ingest.report()?.finishedAt ?? this.ingest.lastRun()?.finishedAt ?? null;
        return finishedAt
            ? new Intl.DateTimeFormat(this.transloco.getActiveLang(), {
                dateStyle: 'short',
                timeStyle: 'short',
            }).format(new Date(finishedAt))
            : null;
    });

  /**
   * When the pass now going started, on the reader's clock.
   *
   * <p>A time and not a date: a run that has been going since yesterday is a run that is
   * stuck, and the missing date is what makes that obvious rather than reassuring.
   */
  protected readonly runStartedAt = computed<string>(() => {
    const startedAt = this.ingest.current()?.startedAt;
    return startedAt
      ? new Intl.DateTimeFormat(this.transloco.getActiveLang(), {timeStyle: 'short'}).format(
        new Date(startedAt),
      )
      : '';
  });

  /**
   * Where the pass has got to, as one string. Assembled here rather than in the template,
   * because the number sits in a different place in every language; the stage name itself
   * is the server's and stays English, like every score reason on this screen.
   */
  protected readonly runStage = computed<string | null>(() => {
    const run = this.ingest.current();
    if (run === null) {
      return null;
    }
    if (run.stage === null || run.stagePosition === null || run.stageTotal === null) {
      return this.transloco.translate('shell.runStageUnknown');
    }
    return this.transloco.translate('shell.runStage', {
      stage: run.stage,
      position: run.stagePosition,
      total: run.stageTotal,
    });
  });

    /**
     * Where the recorded run spent its time, stage by stage.
     *
     * <p>From the recorded run only: an `IngestReport` carries no timings. After this
     * browser's own run the run-ended refresh reads the recorded one back a moment later, so
     * the table and the rest of the panel describe the same pass.
     */
    protected readonly runStages = computed<readonly LastRunStage[]>(() => this.ingest.lastRun()?.stages ?? []);

    /**
     * The position of the stage that took longest, or null when there is nothing to compare.
     * Marked because "the run took eleven minutes" is not actionable and "enrichment took
     * nine of them" is.
     */
    protected readonly slowestStage = computed<number | null>(() => {
        const stages = this.runStages();
        if (stages.length < 2) {
            return null;
        }
        const slowest = stages.reduce((longest, stage) => (stage.millis > longest.millis ? stage : longest));
        return slowest.millis > 0 ? slowest.position : null;
    });

    /**
     * The stage the recorded run stopped in, when it stopped in one. The last timing,
     * because the server appends the failed one last; the run's own status and not the
     * timings decides, since a failed source leaves a FAILED row under a run that completed.
     */
    protected readonly failedStage = computed<LastRunStage | null>(() => {
        const run = this.ingest.lastRun();
        return run?.status === 'FAILED' ? (run.stages.at(-1) ?? null) : null;
    });

    /**
     * A duration in the unit a person reads it in. `Intl` names the unit, so there is no
     * prose here; the locale follows the chosen language like every date on this panel.
     */
    protected duration(millis: number): string {
        const lang = this.transloco.getActiveLang();
        const [unit, value, digits] =
            millis < 1000
                ? (['millisecond', millis, 0] as const)
                : millis < 60_000
                  ? (['second', millis / 1000, 1] as const)
                  : (['minute', millis / 60_000, 1] as const);
        return new Intl.NumberFormat(lang, {
            style: 'unit',
            unit,
            // `short` and not `narrow`: CLDR's narrow German form drops the space for exactly
            // one (`1ms` beside `3 ms`), which reads as a typo in a column of numbers.
            unitDisplay: 'short',
            maximumFractionDigits: digits,
        }).format(value);
    }

    /**
     * Whether that run is one this browser watched. Only the sentence changes: a recorded run
     * additionally says nothing was started here, which is what explains the missing
     * per-document detail below it.
     */
    protected readonly showingRecordedRun = this.ingest.showingRecordedRun;

    /**
     * Counted from `filter_stage` on the offers themselves, which is why the rail can be
     * empty: before the first run there is nothing to count, and a rail showing the
     * measured baseline instead would be a claim about a run that never happened.
     */
    protected readonly stages = computed(() => this.shortlist.funnel()?.stages ?? []);
    protected readonly total = computed(() => this.shortlist.funnel()?.total ?? 0);
    protected readonly survived = computed(() => this.shortlist.funnel()?.survived ?? 0);
    /**
     * Outside the shape, deliberately. Leaving the working list is not something the filter
     * did, and the number is stated because otherwise the rail's total looks wrong: after a
     * week the archive holds most of the table.
     */
    protected readonly archived = computed(() => this.shortlist.funnel()?.archived ?? 0);

    /**
     * A zero and an unreachable board look identical on a tile, and this one is the reason
     * the follow-up dates get entered at all. An em dash says the count is not known.
     */
    protected readonly followUpsDue = computed<number | string>(() =>
        this.applications.error() === null ? this.applications.followUpsDue() : '—',
    );

    ngOnInit(): void {
        this.dispatch.opened();
        this.shortlistDispatch.funnelOpened();
    }

    /**
     * The share of what came in that survived, or nothing to say when nothing came in.
     * A key and its parameters rather than a sentence: no prose is written in TypeScript,
     * and the percentage sits inside the sentence differently in every language.
     */
    protected readonly share = computed(() => {
        const total = this.total();
        return total === 0
            ? {key: 'dashboard.noRunYet', params: {}}
            : {
                key: 'dashboard.shareOfIntake',
                params: {percent: ((this.survived() / total) * 100).toFixed(1)},
            };
    });

    /** Extracted minus written: the same listing seen in two documents. Not deduplication. */
    protected readonly repeats = computed(() => {
        const extracted = this.runExtracted();
        const written = this.runWritten();
        return extracted === null || written === null ? 0 : extracted - written;
    });

    /**
     * Where the two intake numbers came from. A key rather than a sentence, and three cases
     * rather than two: the tile used to claim "from the last run" for a number that was
     * really the whole archive whenever this browser had not run anything.
     */
    protected readonly intakeSource = computed(() => {
        if (this.ingest.report() !== null) {
            return 'dashboard.fromLastRun';
        }
        return this.ingest.lastRun() !== null
            ? 'dashboard.fromRecordedRun'
            : 'dashboard.measuredBaseline';
    });
}
