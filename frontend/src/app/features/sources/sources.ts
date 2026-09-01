import { ChangeDetectionStrategy, Component } from '@angular/core';
import { SOURCES_FIXTURE } from '@core/fixtures/sources.fixture';
import { SourceSummary } from '@core/model/source-summary';
import { Badge } from '@shared/badge/badge';
import { Icon } from '@shared/icon/icon';
import { LgIconName } from '@shared/icon/lucide-icons';
import { PageHeader } from '@shared/page-header/page-header';

@Component({
  selector: 'lg-sources',
  imports: [Badge, Icon, PageHeader],
  templateUrl: './sources.html',
  styleUrl: './sources.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Sources {
  protected readonly sources = SOURCES_FIXTURE;

  protected iconFor(kind: SourceSummary['kind']): LgIconName {
    if (kind === 'imap') {
      return 'inbox';
    }
    return kind === 'rss' ? 'external-link' : 'file-text';
  }

  /**
   * A document that says how many offers it holds is the only check nothing else
   * can make. Silence is not a failure; a mismatch is.
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
