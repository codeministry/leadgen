import {ChangeDetectionStrategy, Component, computed, input} from '@angular/core';
import {BandCounts} from './spark-day';

interface BandSegment {
    readonly id: 'shortlisted' | 'review' | 'discarded' | 'unscored';
    readonly label: string;
    readonly count: number;
    readonly widthPercent: number;
}

/**
 * How the scores fall, as one stacked bar and four counts. The strong band is the signal
 * (it survived the filter and cleared the threshold), the review band secondary, the rest
 * muted — the same three colours the score ring uses for the same three verdicts
 * (`--score-strong`, `--score-weak`, `--score-out`), so the cell and the ring agree.
 *
 * <p>Inline SVG rather than a chart library, for the same reasons as the sparkline: four
 * rectangles, and the tokens reach them as `fill`.
 */
@Component({
    selector: 'lg-score-bands',
    templateUrl: './score-bands.html',
    styleUrl: './score-bands.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScoreBands {
    readonly bands = input.required<BandCounts>();
    /** The four labels, already translated: `shared/` holds no catalog keys. */
    readonly labels = input.required<Readonly<Record<BandSegment['id'], string>>>();

    protected readonly segments = computed<readonly BandSegment[]>(() => {
        const bands = this.bands();
        const labels = this.labels();
        const total = bands.shortlisted + bands.review + bands.discarded + bands.unscored;
        const ids: BandSegment['id'][] = ['shortlisted', 'review', 'discarded', 'unscored'];
        return ids.map((id) => ({
            id,
            label: labels[id],
            count: bands[id],
            widthPercent: total === 0 ? 0 : (bands[id] / total) * 100,
        }));
    });

    protected readonly offsets = computed<readonly number[]>(() => {
        let offset = 0;
        return this.segments().map((segment) => {
            const start = offset;
            offset += segment.widthPercent;
            return start;
        });
    });
}
