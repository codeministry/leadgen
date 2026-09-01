import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { injectDispatch } from '@ngrx/signals/events';
import { shellEvents } from '@core/shell/shell.events';
import { ShellStore } from '@core/shell/shell.store';
import { ingestEvents } from '@core/store/ingest.events';
import { IngestStore } from '@core/store/ingest.store';
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
  private readonly shellDispatch = injectDispatch(shellEvents);
  private readonly ingestDispatch = injectDispatch(ingestEvents);

  protected toggleRail(): void {
    this.shellDispatch.railToggled();
  }

  /** Reading the sources is a pipeline action, not a dashboard one, so it lives here. */
  protected runIngest(): void {
    this.ingestDispatch.requested();
  }
}
