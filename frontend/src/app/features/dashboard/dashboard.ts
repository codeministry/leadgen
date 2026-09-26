import {ChangeDetectionStrategy, Component, computed, inject, OnInit} from '@angular/core';
import {injectDispatch} from '@ngrx/signals/events';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {applicationEvents} from '@core/store/applications.events';
import {shortlistEvents} from '@core/store/shortlist.events';
import {ShortlistStore} from '@core/store/shortlist.store';
import {ApplicationsStore} from '@core/store/applications.store';
import {IngestStore} from '@core/store/ingest.store';
import {SummaryStore} from '@core/store/summary.store';
import {summaryEvents} from '@core/store/summary.events';
import {LastRunStage} from '@core/model/last-run';
import {Badge} from '@shared/badge/badge';
import {EmptyState} from '@shared/empty-state/empty-state';
import {FunnelRail} from '@shared/funnel-rail/funnel-rail';
import {IntakeSpark} from '@shared/chart/intake-spark';
import {ScoreBands} from '@shared/chart/score-bands';
import {DashboardHero} from './dashboard-hero/dashboard-hero';
import {Icon} from '@shared/icon/icon';
import {PageHeader} from '@shared/page-header/page-header';
import {StatTile} from '@shared/stat-tile/stat-tile';
import {LgIconName} from '@shared/icon/lucide-icons';
import {Tone} from '@shared/tone/tone';

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

/** One line of the closed machine room: a label key, a value key and the value's parameters. */
interface MachineRoomFact {
    readonly label: string;
    readonly key: string;
    readonly params: Record<string, unknown>;
}

@Component({
    selector: 'lg-dashboard',
    imports: [Badge, DashboardHero, EmptyState, FunnelRail, Icon, IntakeSpark, PageHeader, ScoreBands, StatTile, TranslocoPipe],
    templateUrl: './dashboard.html',
    styleUrl: './dashboard.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Dashboard implements OnInit {
    private readonly dispatch = injectDispatch(applicationEvents);
    private readonly shortlistDispatch = injectDispatch(shortlistEvents);
    private readonly summaryDispatch = injectDispatch(summaryEvents);
    private readonly transloco = inject(TranslocoService);
    protected readonly ingest = inject(IngestStore);
    protected readonly applications = inject(ApplicationsStore);
    protected readonly shortlist = inject(ShortlistStore);
    protected readonly summary = inject(SummaryStore);

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

    /**
     * What that run wrote. Lower than what it extracted by the listings it read twice — the same
     * project in two newsletters — which is what the hero says in words, so that a run count
     * larger than the archive does not read as a miscount.
     */
    protected readonly runWritten = computed<number | null>(
        () => this.ingest.report()?.written ?? this.ingest.lastRun()?.written ?? null,
    );

    /**
     * The strong matches among the survivors: the score cell's shortlisted band. Null until the
     * summary has answered, so the hero leaves the line out rather than claiming none.
     */
    protected readonly strong = computed<number | null>(() => this.summary.summary()?.scoreBands.shortlisted ?? null);

    /**
     * When the run on screen finished, on the reader's own clock — whichever run it is.
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

    /** Due is a warning, nothing due is good news, and an unknown count is neither. */
    protected readonly followUpsTone = computed<Tone>(() => {
        const due = this.followUpsDue();
        return typeof due !== 'number' ? 'neutral' : due > 0 ? 'warning' : 'success';
    });


    /**
     * The four band labels for the score cell, translated here because `shared/` holds no
     * catalog keys. A signal rather than four pipes in the template, so a language change
     * re-reads them once.
     */
    protected readonly bandLabels = computed(() => ({
        shortlisted: this.transloco.translate('dashboard.bandShortlisted'),
        review: this.transloco.translate('dashboard.bandReview'),
        discarded: this.transloco.translate('dashboard.bandDiscarded'),
        unscored: this.transloco.translate('dashboard.bandUnscored'),
    }));

    /**
     * The run-health cell: one word for the state and one line under it. Read from the
     * run on screen (the report or the recorded row), the same source the machine room
     * describes, so the cell and the room never disagree about the same pass.
     */
    protected readonly runHealth = computed<{
        readonly value: string;
        readonly hint: string;
        readonly params: Record<string, unknown>;
        readonly tone: Tone;
        readonly icon: LgIconName;
    }>(() => {
        const failed = this.failedStage();
        const mismatches = this.mismatches();
        const when = this.finishedAt() ?? '';
        if (!this.hasRun()) {
            return {value: 'dashboard.healthNone', hint: 'dashboard.healthNoneHint', params: {}, tone: 'neutral', icon: 'circle-dot'};
        }
        if (failed !== null) {
            return {
                value: 'dashboard.healthFailed',
                hint: 'dashboard.healthFailedHint',
                params: {stage: failed.stage, when},
                tone: 'error',
                icon: 'circle-x',
            };
        }
        if (mismatches > 0) {
            return {
                value: 'dashboard.healthShort',
                hint: 'dashboard.healthShortHint',
                params: {count: mismatches, when},
                tone: 'warning',
                icon: 'triangle-alert',
            };
        }
        return {value: 'dashboard.healthOk', hint: 'dashboard.healthOkHint', params: {when}, tone: 'success', icon: 'circle-check'};
    });

    /**
     * The machine room opens itself when there is something in it a person must see: a
     * failed run, or a source that came up short. Otherwise it is one click away, which is
     * where the per-source table and the timings belong on a normal morning.
     */
    protected readonly machineRoomOpen = computed(() => this.failedStage() !== null || this.mismatches() > 0);


    /**
     * What the machine room holds, in one line on its closed fold: which run, how many
     * sources it read, what it brought, how long it took and which judge answered. The
     * numbers are the room's own numbers, so the preview cannot say something the table
     * does not. A screen with nothing recorded says so instead of a row of zeros.
     */
    protected readonly machineRoomPreview = computed<{
        readonly key: string;
        readonly params: Record<string, unknown>;
    }>(() => {
        if (!this.hasRun()) {
            return {key: 'dashboard.machineRoomEmpty', params: {}};
        }
        const millis = this.runStages().reduce((sum, stage) => sum + stage.millis, 0);
        const model = this.analysed()?.model ?? null;
        return {
            key: model === null ? 'dashboard.machineRoomPreview' : 'dashboard.machineRoomPreviewModel',
            params: {
                when: this.finishedAt() ?? '',
                sources: this.runSources().length,
                count: this.runExtracted() ?? 0,
                duration: millis > 0 ? this.duration(millis) : '',
                model,
            },
        };
    });

    /**
     * The closed fold's four lines under the preview: sources, hard filter, stages, outcome. Read
     * off the recorded run, which is the one that carries the filter and outcome counts; a
     * closed disclosure with one line under five cells read as an empty page, and four lines of
     * the run's own numbers are what the fold holds anyway.
     */
    protected readonly machineRoomFacts = computed<readonly MachineRoomFact[]>(() => {
        const run = this.ingest.lastRun();
        if (run === null) {
            return [];
        }
        const sources = this.runSources();
        const removedEntries = Object.entries(run.removed);
        const removed = removedEntries.reduce((sum, [, count]) => sum + count, 0);
        const mostRemoved = removedEntries.reduce<[string, number] | null>(
            (top, entry) => (top === null || entry[1] > top[1] ? entry : top),
            null,
        );
        const stages = this.runStages();
        const slowest = this.slowestStage();
        const slowestStage = slowest === null ? null : (stages.find((stage) => stage.position === slowest) ?? null);
        return [
            {
                label: 'dashboard.factSources',
                key: 'dashboard.factSourcesValue',
                params: {
                    sources: sources.length,
                    documents: sources.reduce((sum, source) => sum + source.documents, 0),
                    mismatches: this.mismatches(),
                },
            },
            {
                label: 'dashboard.factFilter',
                key: 'dashboard.factFilterValue',
                params: {considered: run.filterConsidered, passed: run.filterPassed, removed, stage: mostRemoved?.[0] ?? ''},
            },
            ...(this.stages().length > 0
                ? [
                      {
                          label: 'dashboard.factFilterStages',
                          key: 'dashboard.factFilterStagesValue',
                          params: {stages: this.stageRemovals()},
                      },
                  ]
                : []),
            {
                label: 'dashboard.factStages',
                key: slowestStage === null ? 'dashboard.factStagesFlat' : 'dashboard.factStagesValue',
                params: {
                    count: stages.length,
                    stage: slowestStage?.stage ?? '',
                    duration: slowestStage === null ? '' : this.duration(slowestStage.millis),
                },
            },
            {
                label: 'dashboard.factOutcome',
                key: 'dashboard.factOutcomeValue',
                params: {
                    scored: run.scored,
                    shortlisted: run.shortlisted,
                    review: run.review,
                    digest: run.digestWritten ? 'yes' : 'no',
                },
            },
        ];
    });

    /** The hard filter's stages in run order, each with what it removed: "Abroad −8 · …". */
    private stageRemovals(): string {
        const format = new Intl.NumberFormat(this.transloco.getActiveLang());
        return this.stages()
            .map((stage) => `${stage.label} −${format.format(stage.removed)}`)
            .join(' · ');
    }

    ngOnInit(): void {
        this.dispatch.opened();
        this.shortlistDispatch.funnelOpened();
        this.summaryDispatch.opened();
    }

    /** Extracted minus written: the same listing seen in two documents. Not deduplication. */
}
