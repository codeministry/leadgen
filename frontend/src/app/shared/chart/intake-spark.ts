import {ChangeDetectionStrategy, Component, computed, input} from '@angular/core';
import {SparkDay} from './spark-day';

interface SparkBar {
    readonly day: string;
    readonly extracted: number;
    readonly shortlisted: number;
    /** Heights on 0..100 of the cell, the taller of the two series at 100. */
    readonly extractedHeight: number;
    readonly shortlistedHeight: number;
}

/**
 * Fourteen days of intake as bars, inline SVG rather than a chart library: fourteen
 * rectangles do not need zrender, and an SVG takes the theme's tokens as `fill` without a
 * palette being resolved first. Extracted is the primary, shortlisted is the signal — the
 * one place on the dashboard's small cells where the signal appears, and it means what it
 * always means: this survived the filter (spec 006, ISC-268).
 *
 * <p>The numbers are in the DOM as a table for a screen reader, the same rule every chart
 * in `shared/chart/` follows; the drawing is `aria-hidden`.
 */
@Component({
    selector: 'lg-intake-spark',
    templateUrl: './intake-spark.html',
    styleUrl: './intake-spark.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IntakeSpark {
    readonly days = input.required<readonly SparkDay[]>();
    /** Column headings for the table. Already translated: `shared/` holds no catalog keys. */
    readonly extractedLabel = input.required<string>();
    readonly shortlistedLabel = input.required<string>();

    protected readonly bars = computed<readonly SparkBar[]>(() => {
        const days = this.days();
        const peak = Math.max(1, ...days.map((day) => day.extracted));
        return days.map((day) => ({
            day: day.day,
            extracted: day.extracted,
            shortlisted: day.shortlisted,
            extractedHeight: (day.extracted / peak) * 100,
            shortlistedHeight: (day.shortlisted / peak) * 100,
        }));
    });

    protected readonly total = computed(() => this.days().reduce((sum, day) => sum + day.extracted, 0));
    protected readonly totalShortlisted = computed(() =>
        this.days().reduce((sum, day) => sum + day.shortlisted, 0),
    );
}
