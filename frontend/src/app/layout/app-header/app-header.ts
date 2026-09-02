import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { injectDispatch } from '@ngrx/signals/events';
import { shellEvents } from '@core/shell/shell.events';
import { ShellStore } from '@core/shell/shell.store';
import { ingestEvents } from '@core/store/ingest.events';
import { IngestStore } from '@core/store/ingest.store';
import { scoringModelEvents } from '@core/store/scoring-model.events';
import { ScoringModelStore } from '@core/store/scoring-model.store';
import { StatusStore } from '@core/store/status.store';
import { BrandMark } from '@shared/brand-mark/brand-mark';
import { Icon } from '@shared/icon/icon';
import { TranslocoPipe } from '@jsverse/transloco';
import { LanguageToggle } from '../language-toggle/language-toggle';
import { ThemeToggle } from '../theme-toggle/theme-toggle';

@Component({
  selector: 'lg-app-header',
  imports: [BrandMark, Icon, RouterLink, ThemeToggle, LanguageToggle, TranslocoPipe],
  templateUrl: './app-header.html',
  styleUrl: './app-header.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppHeader {
  protected readonly status = inject(StatusStore);
  protected readonly shell = inject(ShellStore);
  protected readonly ingest = inject(IngestStore);
  protected readonly models = inject(ScoringModelStore);
  private readonly shellDispatch = injectDispatch(shellEvents);
  private readonly ingestDispatch = injectDispatch(ingestEvents);
  private readonly modelDispatch = injectDispatch(scoringModelEvents);

  protected toggleRail(): void {
    this.shellDispatch.railToggled();
  }

  /** Reading the sources is a pipeline action, not a dashboard one, so it lives here. */
  protected runIngest(): void {
    this.ingestDispatch.requested();
  }

  /**
   * The choice reaches the request through `ScoringModelStore`, not through the run event:
   * the rescore button on the offer detail has to ask the same judge, and two components
   * handing over a model would be two places that can disagree about which one is current.
   */
  protected chooseModel(event: Event): void {
    this.modelDispatch.chosen((event.target as HTMLSelectElement).value);
  }
}
