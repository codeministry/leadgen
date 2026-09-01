import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Icon } from '@shared/icon/icon';
import { LgIconName } from '@shared/icon/lucide-icons';

/**
 * An empty screen is an invitation to act, so the description says what to do
 * next rather than restating that there is nothing here.
 */
@Component({
  selector: 'lg-empty-state',
  imports: [Icon],
  templateUrl: './empty-state.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmptyState {
  readonly icon = input<LgIconName>('inbox');
  readonly title = input.required<string>();
  readonly description = input.required<string>();
}
