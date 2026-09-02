import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { inject } from '@angular/core';
import { MarketView } from '@core/model/analytics';
import { RankedBarChart } from '@shared/chart/ranked-bar-chart';
import { RankedBar } from '@shared/chart/ranked-bar';

/**
 * Where the offers come from, what they are filed under, and where the work sits.
 *
 * <p>Three ranked lists, and each of them carries a caveat the heading has to state rather
 * than the chart imply. The tags are the aggregator's own filing categories and not skills
 * read out of an advert. The locations are free text, unnormalised, so "Remote und
 * Nürnberg" and "Nürnberg" are two rows — which is why the counts the *filter* decided sit
 * beside them, as the version of the same question that is not guessed.
 */
@Component({
  selector: 'lg-market-panel',
  imports: [RankedBarChart, TranslocoPipe],
  templateUrl: './market-panel.html',
  styleUrl: './market-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MarketPanel {
  readonly market = input.required<MarketView>();

  private readonly transloco = inject(TranslocoService);

  /** Listings, with the projects that cleared the filter marked inside them. */
  protected readonly portals = computed<readonly RankedBar[]>(() =>
    this.market().portals.map((portal) => ({
      label: portal.portal,
      value: portal.listings,
      secondary: portal.passed,
    })),
  );

  protected readonly tags = computed<readonly RankedBar[]>(() =>
    this.market().tags.map((tag) => ({ label: tag.tag, value: tag.projects, secondary: tag.passed })),
  );

  protected readonly locations = computed<readonly RankedBar[]>(() =>
    this.market()
      .locations.map((location) => ({
        label: location.location,
        value: location.projects,
        secondary: location.passed,
      })),
  );

  protected readonly labels = computed(() => ({
    listings: this.transloco.translate('analytics.colListings'),
    projects: this.transloco.translate('analytics.colProjects'),
    passed: this.transloco.translate('analytics.colPassed'),
  }));
}
