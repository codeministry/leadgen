import {ChangeDetectionStrategy, Component, computed, input} from '@angular/core';

/**
 * The brand lockup: the logo asset plus the wordmark beside it.
 *
 * `public/logo-mark.png` is `logo-1.png` cut out, trimmed and resized to 128 px
 * tall — four times the 26 px the header shows, so it stays crisp on a retina
 * display. The favicon comes from the same source on a round plate, so the tab
 * icon and the header show the same funnel.
 *
 * The asset is used as a CSS mask rather than as an image, so the mark takes the
 * theme's primary. A mask has no intrinsic size, which is why both dimensions are
 * set here instead of leaving the width to `auto`.
 */
@Component({
    selector: 'lg-brand-mark',
    templateUrl: './brand-mark.html',
    styleUrl: './brand-mark.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BrandMark {
  /** The asset's own pixel box, and the only place the ratio is written down. */
  private static readonly INTRINSIC = {width: 116, height: 128};

    readonly size = input(28);
    readonly wordmark = input(true);
    /** Carried by the wordmark when it is visible, by the mark alone when it is not. */
    readonly label = input('Lead Generation');

  protected readonly width = computed(() =>
    Math.round((this.size() * BrandMark.INTRINSIC.width) / BrandMark.INTRINSIC.height),
  );
}
