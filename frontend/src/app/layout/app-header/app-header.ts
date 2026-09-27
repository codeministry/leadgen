import {ChangeDetectionStrategy, Component, inject, signal} from '@angular/core';
import {RouterLink} from '@angular/router';
import {StatusStore} from '@core/store/status.store';
import {BrandMark} from '@shared/brand-mark/brand-mark';
import {AppNav} from '../app-nav/app-nav';
import {Icon} from '@shared/icon/icon';
import {TranslocoPipe} from '@jsverse/transloco';
import {LanguageToggle} from '../language-toggle/language-toggle';
import {ThemeToggle} from '../theme-toggle/theme-toggle';
import {HelpDrawer} from '../help-drawer/help-drawer';

/**
 * The chrome on every screen: the brand, the navigation, settings and help.
 *
 * <p>Run ingest and the model choice are not here any more (operator, 2026-09-27). Starting a run
 * is a pipeline action, so it sits on the workflow screen, directly above the status chip that
 * follows the run it starts — `features/rules/run-control/`.
 */
@Component({
    selector: 'lg-app-header',
  imports: [AppNav, BrandMark, HelpDrawer, Icon, RouterLink, ThemeToggle, LanguageToggle, TranslocoPipe],
    templateUrl: './app-header.html',
    styleUrl: './app-header.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppHeader {
    protected readonly status = inject(StatusStore);

  /**
   * Mirrored from the panel's own `toggle` event rather than tracked on the click, so a
   * light dismiss and an Escape are as visible to a screen reader as the button is.
   */
  protected readonly settingsOpen = signal(false);

  protected onSettingsToggle(event: Event): void {
    this.settingsOpen.set((event as ToggleEvent).newState === 'open');
    }
}
