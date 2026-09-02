import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { injectDispatch } from '@ngrx/signals/events';
import { analyticsEvents } from '@core/store/analytics.events';
import { AnalyticsStore } from '@core/store/analytics.store';
import { EmptyState } from '@shared/empty-state/empty-state';
import { PageHeader } from '@shared/page-header/page-header';
import { StatTile } from '@shared/stat-tile/stat-tile';
import {
  Granularity,
  TimeAxis,
  bucketBy,
  daysOf,
  isGranularity,
  isTimeAxis,
  perWeek,
  share,
  suggestedGranularity,
} from './analytics-aggregate';
import { ApplicationsPanel } from './applications-panel/applications-panel';
import { IntakePanel } from './intake-panel/intake-panel';
import { MarketPanel } from './market-panel/market-panel';
import { RunsPanel } from './runs-panel/runs-panel';
import { ScoresPanel } from './scores-panel/scores-panel';
import { StageMixPanel } from './stage-mix-panel/stage-mix-panel';

/**
 * What the market is doing, and what the rules are doing to it.
 *
 * <p>The two switches live in the query string rather than in the component, for the same
 * reason the shortlist's filters do: a view worth discussing is a link somebody can send.
 * Both need the transform — router input binding writes `undefined` for a parameter absent
 * from the URL, over the declared default, and the page then half-renders with nothing in
 * the console pointing anywhere near the cause.
 *
 * <p><b>`published` is the default axis.</b> It is what the market advertised; the ingest
 * axis also measures how often the tool was run, which is a fact about the operator rather
 * than about the market.
 */
@Component({
  selector: 'lg-analytics',
  imports: [
    ApplicationsPanel,
    EmptyState,
    IntakePanel,
    MarketPanel,
    PageHeader,
    RunsPanel,
    ScoresPanel,
    StageMixPanel,
    StatTile,
    TranslocoPipe,
  ],
  templateUrl: './analytics.html',
  styleUrl: './analytics.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Analytics implements OnInit {
  private readonly router = inject(Router);
  private readonly dispatch = injectDispatch(analyticsEvents);
  protected readonly store = inject(AnalyticsStore);

  readonly axis = input<TimeAxis, TimeAxis | undefined>('received', {
    transform: (value) => (isTimeAxis(value) ? value : 'received'),
  });
  /**
   * Null until somebody picks one, so "nothing chosen" and "week chosen" stay apart. An
   * unchosen granularity follows the span: two days bucketed by week are a single bar
   * labelled with that week's Monday, which reads as though everything happened that day.
   */
  readonly by = input<Granularity | null, Granularity | undefined>(null, {
    transform: (value) => (isGranularity(value) ? value : null),
  });

  protected readonly axisOptions: readonly { id: TimeAxis; label: string }[] = [
    { id: 'received', label: 'analytics.axisReceived' },
    { id: 'published', label: 'analytics.axisPublished' },
    { id: 'ingested', label: 'analytics.axisIngested' },
  ];

  protected readonly granularities: readonly { id: Granularity; label: string }[] = [
    { id: 'day', label: 'analytics.byDay' },
    { id: 'week', label: 'analytics.byWeek' },
    { id: 'month', label: 'analytics.byMonth' },
  ];

  ngOnInit(): void {
    this.dispatch.opened();
  }

  private readonly days = computed(() => {
    const view = this.store.view();
    return view === null ? [] : daysOf(view.intake, this.axis());
  });

  protected readonly granularity = computed(() => this.by() ?? suggestedGranularity(this.days()));

  protected readonly buckets = computed(() => bucketBy(this.days(), this.granularity()));

  /**
   * What the published axis cannot show, and only that axis.
   *
   * <p>A window that leaves offers out without saying so is the same failure as a selector
   * that quietly stopped matching: the chart looks right and the archive is bigger than it.
   * Null when there is nothing to declare, so the line does not appear saying zero.
   */
  protected readonly excluded = computed(() => {
    const intake = this.store.view()?.intake;
    if (intake === undefined || this.axis() !== 'published') {
      return null;
    }
    const total = intake.withoutPublishedOn + intake.publishedOutOfRange;
    return total === 0
      ? null
      : { undated: intake.withoutPublishedOn, outOfRange: intake.publishedOutOfRange };
  });

  /** The received axis leaves out whatever did not come in the post, and says how much. */
  protected readonly withoutMail = computed(() => {
    const intake = this.store.view()?.intake;
    if (intake === undefined || this.axis() !== 'received' || intake.withoutReceivedAt === 0) {
      return null;
    }
    return intake.withoutReceivedAt;
  });

  protected readonly total = computed(() => this.store.view()?.funnel.total ?? 0);

  /** An em dash until the span is a week: below that a weekly rate is an extrapolation. */
  protected readonly perWeek = computed(() => {
    const rate = perWeek(this.days());
    return rate === null ? '—' : Math.round(rate);
  });

  protected readonly perWeekHint = computed(() =>
    perWeek(this.days()) === null ? 'analytics.tilePerWeekTooShort' : 'analytics.tilePerWeekHint',
  );

  /** The share of what came in that cleared the filter, or an em dash when nothing has. */
  protected readonly passRate = computed(() => {
    const view = this.store.view();
    const value = view === null ? null : share(view.funnel.survived, view.funnel.total);
    return value === null ? '—' : `${value.toFixed(1)} %`;
  });

  /**
   * How often an application is answered. An em dash rather than 0 % when nothing has been
   * sent: a rate over nothing is not zero, it is unknown, and a zero here would read as
   * "nobody ever answers".
   */
  protected readonly responseRate = computed(() => {
    const response = this.store.view()?.applications.response;
    const value = response === undefined ? null : share(response.answered, response.sent);
    return value === null ? '—' : `${value.toFixed(0)} %`;
  });

  /** Set on the URL, not in the component, so a reload and a shared link both survive. */
  protected select(parameter: 'axis' | 'by', value: string): void {
    void this.router.navigate([], {
      queryParams: { [parameter]: value },
      queryParamsHandling: 'merge',
    });
  }
}
