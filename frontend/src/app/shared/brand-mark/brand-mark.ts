import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The brand lockup: the logo asset plus the wordmark beside it.
 *
 * `public/logo-mark.png` is `logo-1.png` cut out, trimmed and resized to 128 px
 * tall — four times the 26 px the header shows, so it stays crisp on a retina
 * display. The favicon comes from the same source on the brand's navy plate, so
 * the tab icon and the header show the same funnel.
 */
@Component({
  selector: 'lg-brand-mark',
  templateUrl: './brand-mark.html',
  styleUrl: './brand-mark.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BrandMark {
  readonly size = input(28);
  readonly wordmark = input(true);
  /** Carried by the wordmark when it is visible, by the mark alone when it is not. */
  readonly label = input('Lead Generation');
}
