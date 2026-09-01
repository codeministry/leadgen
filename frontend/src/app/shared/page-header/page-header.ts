import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'lg-page-header',
  templateUrl: './page-header.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PageHeader {
  readonly title = input.required<string>();
  /** One line saying what the screen is for. Omitted when the title is enough. */
  readonly subtitle = input<string | null>(null);
}
