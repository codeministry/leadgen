import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'lg-stat-tile',
  templateUrl: './stat-tile.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatTile {
  readonly label = input.required<string>();
  readonly value = input.required<string | number>();
  readonly unit = input<string | null>(null);
  readonly hint = input<string | null>(null);
  /** `accent` is for the one figure on the screen that the morning is about. */
  readonly emphasis = input(false);
}
