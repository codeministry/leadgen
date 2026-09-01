import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { injectDispatch } from '@ngrx/signals/events';
import { TranslocoPipe } from '@jsverse/transloco';
import { SourceSummary } from '@core/model/source-summary';
import { configEvents } from '@core/store/config.events';
import { ConfigStore } from '@core/store/config.store';
import { Badge } from '@shared/badge/badge';
import { EmptyState } from '@shared/empty-state/empty-state';
import { Icon } from '@shared/icon/icon';
import { LgIconName } from '@shared/icon/lucide-icons';
import { PageHeader } from '@shared/page-header/page-header';

@Component({
  selector: 'lg-sources',
  imports: [Badge, EmptyState, Icon, PageHeader, TranslocoPipe],
  templateUrl: './sources.html',
  styleUrl: './sources.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Sources implements OnInit {
  private readonly dispatch = injectDispatch(configEvents);
  protected readonly store = inject(ConfigStore);

  ngOnInit(): void {
    this.dispatch.sourcesOpened();
  }

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

  /** The instant carries a time nobody needs to the second. */
  protected day(instant: string | null): string {
    return instant === null ? '—' : instant.slice(0, 10);
  }
}
