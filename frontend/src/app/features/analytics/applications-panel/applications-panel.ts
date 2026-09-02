import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ApplicationAnalytics } from '@core/model/analytics';
import { RankedBar } from '@shared/chart/ranked-bar';
import { RankedBarChart } from '@shared/chart/ranked-bar-chart';

/**
 * The half of the loop nothing else measures.
 *
 * <p>Every number here was typed by the operator, because nothing observes a sent mail.
 * That is also why the panel has to be honest about how little it may be standing on: a
 * median over three answers is theatre, so the count it was computed over is printed
 * beside it and the median is suppressed entirely below a handful.
 */
@Component({
  selector: 'lg-applications-panel',
  imports: [RankedBarChart, TranslocoPipe],
  templateUrl: './applications-panel.html',
  styleUrl: './applications-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ApplicationsPanel {
  readonly applications = input.required<ApplicationAnalytics>();

  /** Below this, a median says more about the sample than about the market. */
  private static readonly ENOUGH_TO_AVERAGE = 5;

  private readonly transloco = inject(TranslocoService);

  protected readonly total = computed(() =>
    this.applications().byStatus.reduce((sum, status) => sum + status.applications, 0),
  );

  /** Every state, including the empty ones — a lane nothing is in is a fact about the board. */
  protected readonly bars = computed<readonly RankedBar[]>(() =>
    this.applications().byStatus.map((status) => ({
      label: status.status,
      value: status.applications,
      secondary: 0,
    })),
  );

  protected readonly labels = computed(() => ({
    applications: this.transloco.translate('analytics.colApplications'),
    answered: this.transloco.translate('analytics.colAnswered'),
  }));

  /**
   * The median, or nothing. Null when nothing was answered, and withheld when too little
   * was: a number that would be read as a fact about the market when it is a fact about
   * four applications.
   */
  protected readonly median = computed(() => {
    const response = this.applications().response;
    return response.answered >= ApplicationsPanel.ENOUGH_TO_AVERAGE
      ? response.medianDaysToFirstReply
      : null;
  });
}
