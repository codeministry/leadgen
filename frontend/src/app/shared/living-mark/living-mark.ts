import {ChangeDetectionStrategy, Component, input} from '@angular/core';

/** The four states the mark can show; the host writes the chosen one as `data-frame`. */
export type LivingMarkFrame = 'rest' | 'working' | 'speaking' | 'halted';

/**
 * The lead ring brought to life: the brand mark of `shared/brand-mark` drawn a second time,
 * with four frames a host picks by one input (spec 021, ISC-464).
 *
 * <p>The component maps the input onto the host's `data-frame` attribute and does nothing
 * else; every frame is written in the stylesheet against that attribute, so a stylesheet and
 * a test read the same word. The ring's stroke is the theme's primary in every frame, the
 * dots paint `currentColor`, and the host's `color` defaults to the signal, which makes
 * `rest` the brand mark wherever the component is placed. A host that wants another colour
 * for the dots sets `color` on the element; nothing reaches the ring.
 */
@Component({
    selector: 'lg-living-mark',
    templateUrl: './living-mark.html',
    styleUrl: './living-mark.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: {'[attr.data-frame]': 'frame()'},
})
export class LivingMark {
    readonly frame = input<LivingMarkFrame>('rest');
    /** Edge length in px. The viewBox is square, so one number sizes both axes. */
    readonly size = input(20);
}
