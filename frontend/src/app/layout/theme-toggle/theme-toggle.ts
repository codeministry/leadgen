import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { injectDispatch } from '@ngrx/signals/events';
import { themeEvents } from '@core/theme/theme.events';
import { ThemePreference } from '@core/theme/theme.model';
import { ThemeStore } from '@core/theme/theme.store';
import { Icon } from '@shared/icon/icon';
import { LgIconName } from '@shared/icon/lucide-icons';

interface ThemeOption {
  readonly preference: ThemePreference;
  readonly icon: LgIconName;
  readonly label: string;
}

@Component({
  selector: 'lg-theme-toggle',
  imports: [Icon],
  templateUrl: './theme-toggle.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ThemeToggle {
  protected readonly store = inject(ThemeStore);
  private readonly dispatch = injectDispatch(themeEvents);

  protected readonly options: readonly ThemeOption[] = [
    { preference: 'system', icon: 'monitor', label: 'Match the system' },
    { preference: 'light', icon: 'sun', label: 'Light' },
    { preference: 'dark', icon: 'moon', label: 'Dark' },
  ];

  protected choose(preference: ThemePreference): void {
    this.dispatch.chosen(preference);
  }
}
