import {ChangeDetectionStrategy, Component, computed, inject, OnInit} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {ActivatedRoute, NavigationEnd, Router, RouterLink, RouterOutlet} from '@angular/router';
import {filter, map} from 'rxjs';
import {injectDispatch} from '@ngrx/signals/events';
import {TranslocoPipe} from '@jsverse/transloco';
import {SourceSummary} from '@core/model/source-summary';
import {configEvents} from '@core/store/config.events';
import {ConfigStore} from '@core/store/config.store';
import {Badge} from '@shared/badge/badge';
import {EmptyState} from '@shared/empty-state/empty-state';
import {Icon} from '@shared/icon/icon';
import {LgIconName} from '@shared/icon/lucide-icons';
import {DayPipe} from '@shared/date/day.pipe';
import {PageHeader} from '@shared/page-header/page-header';

@Component({
    selector: 'lg-sources',
  imports: [Badge, DayPipe, EmptyState, Icon, PageHeader, RouterLink, RouterOutlet, TranslocoPipe],
    templateUrl: './sources.html',
    styleUrl: './sources.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Sources implements OnInit {
    private readonly dispatch = injectDispatch(configEvents);
    protected readonly store = inject(ConfigStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

    ngOnInit(): void {
        this.dispatch.sourcesOpened();
    }

  /**
   * The URL, only as a reason to look again — the shape the shortlist and the shell both use.
   * Route state is read from the snapshot, so something has to say when the snapshot changed,
   * and RxJS stays at the I/O boundary, which the router is.
   */
  private readonly navigated = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    {initialValue: this.router.url},
  );

  /**
   * Which source the child route is showing. Read from the route rather than held here: the
   * URL is what a deep link, the back button and a click all agree on, and a second copy in a
   * signal disagrees with it the first time one of the three is used.
   */
  protected readonly openId = computed<string | null>(() => {
    this.navigated();
    return this.route.snapshot.firstChild?.paramMap.get('id') ?? null;
  });

    /** A source type is whatever the YAML declares, so the fallback is the interesting case. */
    protected iconFor(kind: SourceSummary['kind']): LgIconName {
        if (kind === 'imap') {
            return 'inbox';
        }
        return kind === 'rss' ? 'external-link' : 'file-text';
    }

    /**
     * A document that says how many offers it holds is the only check nothing else can
     * make. Silence is not a failure; a mismatch is.
     */
    protected shortfall(source: SourceSummary): number | null {
        if (source.announced === null) {
            return null;
        }
        return source.announced - source.extracted;
    }

    protected hitRate(source: SourceSummary): string {
        if (source.extracted === 0) {
            return '—';
        }
        return `${((source.survived / source.extracted) * 100).toFixed(1)} %`;
    }


}
