import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { injectDispatch } from '@ngrx/signals/events';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { applicationEvents } from '@core/store/applications.events';
import { shortlistEvents } from '@core/store/shortlist.events';
import { ShortlistStore } from '@core/store/shortlist.store';
import { ApplicationsStore } from '@core/store/applications.store';
import { IngestStore } from '@core/store/ingest.store';
import { Badge } from '@shared/badge/badge';
import { EmptyState } from '@shared/empty-state/empty-state';
import { FunnelRail } from '@shared/funnel-rail/funnel-rail';
import { Icon } from '@shared/icon/icon';
import { PageHeader } from '@shared/page-header/page-header';
import { StatTile } from '@shared/stat-tile/stat-tile';

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
          ? [{ documentId: null, extracted: source.extracted, announced: source.announced }]
          : [],
    }));
  });

  protected readonly hasRun = computed(
    () => this.ingest.report() !== null || this.ingest.lastRun() !== null,
  );

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
   * When the recorded run finished, on the reader's own clock.
   *
   * <p>The reason this panel is worth loading from the server at all: without it the reader
   * cannot tell tonight's pass from their own click. `finishedAt` is an ISO instant in UTC,
   * and slicing the string would show an 08:47 run as 06:47 — the same trap the runs panel
   * documents. The locale follows the chosen language, because 09/02 and 02.09. are the
   * same day written for two readers.
   */
  protected readonly recordedAt = computed<string | null>(() => {
    const finishedAt = this.ingest.showingRecordedRun() ? this.ingest.lastRun()?.finishedAt : null;
    return finishedAt
      ? new Intl.DateTimeFormat(this.transloco.getActiveLang(), {
          dateStyle: 'short',
          timeStyle: 'short',
        }).format(new Date(finishedAt))
      : null;
  });

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
      ? { key: 'dashboard.noRunYet', params: {} }
      : {
          key: 'dashboard.shareOfIntake',
          params: { percent: ((this.survived() / total) * 100).toFixed(1) },
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
