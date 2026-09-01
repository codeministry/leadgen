import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { IconNode, LG_ICONS, LgIconName } from './lucide-icons';

/**
 * The whole icon dependency, behind one component. Templates say
 * `<lg-icon name="funnel" />` and never see lucide.
 */
@Component({
  selector: 'lg-icon',
  templateUrl: './icon.html',
  styleUrl: './icon.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Icon {
  readonly name = input.required<LgIconName>();
  readonly size = input(20);
  readonly strokeWidth = input(2);
  /**
   * Null keeps the icon decorative. Pass a label only when the icon is the sole
   * carrier of meaning; beside a visible word it would be read twice.
   */
  readonly label = input<string | null>(null);

  protected readonly node = computed<IconNode>(() => LG_ICONS[this.name()]);
}
