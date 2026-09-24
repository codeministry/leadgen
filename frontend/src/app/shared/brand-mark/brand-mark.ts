import {ChangeDetectionStrategy, Component, computed, input} from '@angular/core';

/**
 * The brand lockup: the lead ring plus the wordmark beside it.
 *
 * The mark is the score ring from the shortlist, open at the top right, with the lead
 * inside — chosen by the operator from three candidates (spec 003, 2026-09-24). It is
 * inline SVG rather than a masked bitmap because it is two colours: the ring takes the
 * theme's primary, the two dots take the signal, and a CSS mask paints only one. The same
 * paths live in `frontend/brand/mark.svg`, the one source `tools/build-favicon.sh` reads.
 */
@Component({
    selector: 'lg-brand-mark',
    templateUrl: './brand-mark.html',
    styleUrl: './brand-mark.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BrandMark {
    /** The mark's viewBox, and the only place the ratio is written down. Square. */
    private static readonly INTRINSIC = {width: 32, height: 32};

    readonly size = input(28);
    readonly wordmark = input(true);
    /** Carried by the wordmark when it is visible, by the mark alone when it is not. */
    readonly label = input('Annusa');

    protected readonly width = computed(() =>
        Math.round((this.size() * BrandMark.INTRINSIC.width) / BrandMark.INTRINSIC.height),
    );
}
