import {ChangeDetectionStrategy, Component, computed, input} from '@angular/core';
import {TranslocoPipe} from '@jsverse/transloco';
import {FunnelStage} from '@shared/funnel-rail/funnel-stage';
import {layoutSieve, SIEVE_CENTRE, SIEVE_RING} from './sieve-layout';

/**
 * The dashboard's eyecatcher: the archive drawn as a sieve inside the brand mark.
 *
 * <p>Every offer is a dot. The ones a hard-filter stage held back scatter in that stage's
 * ring, the first stage outermost, and the survivors gather in the core in the signal, the
 * strong matches at its centre. The open ring around it all and the dot in its gap are the
 * logo, so the mark is the product explaining itself.
 *
 * <p>It is a picture of numbers the screen already states in words, so the drawing is
 * `aria-hidden` and the legend under it carries the same counts as text. On first paint the
 * rings fill from the outside in; for a run that brought nothing (`still`) they are simply
 * there, because nothing arrived that could be shown arriving.
 */
@Component({
    selector: 'lg-sieve',
    imports: [TranslocoPipe],
    templateUrl: './sieve.html',
    styleUrl: './sieve.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Sieve {
    readonly stages = input.required<readonly FunnelStage[]>();
    readonly survived = input.required<number>();
    /** The strong matches among the survivors, or zero while the scores are not known. */
    readonly strong = input(0);
    /** No sift on first paint: the run on screen brought nothing. */
    readonly still = input(false);

    protected readonly centre = SIEVE_CENTRE;
    protected readonly ring = SIEVE_RING;
    /** The lead in the ring's gap, where the logo has it: on the ring's diagonal, just outside it. */
    protected readonly lead = {
        x: SIEVE_CENTRE + SIEVE_RING * 1.12 * Math.cos(-Math.PI / 4.6),
        y: SIEVE_CENTRE + SIEVE_RING * 1.12 * Math.sin(-Math.PI / 4.6),
    };
    /** The logo's ring, open over the top-right quarter: from twelve o'clock round to three. */
    protected readonly ringPath = `M ${SIEVE_CENTRE} ${SIEVE_CENTRE - SIEVE_RING} A ${SIEVE_RING} ${SIEVE_RING} 0 1 0 ${SIEVE_CENTRE + SIEVE_RING} ${SIEVE_CENTRE}`;

    protected readonly layout = computed(() => layoutSieve(this.stages(), this.survived(), this.strong()));
    protected readonly heldBack = computed(() => this.stages().reduce((sum, stage) => sum + stage.removed, 0));
    protected readonly stageCount = computed(() => this.stages().filter((stage) => stage.removed > 0).length);

    /** Each band starts its sift one stagger after the one outside it; the core comes last. */
    protected delay(index: number): string {
        return `calc(var(--lg-sift-stagger) * ${index})`;
    }
}
