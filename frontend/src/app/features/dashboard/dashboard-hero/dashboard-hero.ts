import {ChangeDetectionStrategy, Component, computed, input} from '@angular/core';
import {RouterLink} from '@angular/router';
import {TranslocoPipe} from '@jsverse/transloco';
import {FunnelRail} from '@shared/funnel-rail/funnel-rail';
import {FunnelStage} from '@shared/funnel-rail/funnel-stage';

/**
 * The control room's first read: one number, one sentence, one bar, one button.
 *
 * <p>The number is the standing shortlist — what survived the filter — in the signal, at
 * display size. The sentence beside it names the total it came out of. What the morning
 * brought is a separate line and is honest about a quiet night: a run that extracted
 * nothing says so in words, and the survivor count is never presented as this morning's
 * catch when it is last week's (spec 006, ISC-265).
 */
@Component({
    selector: 'lg-dashboard-hero',
    imports: [FunnelRail, RouterLink, TranslocoPipe],
    templateUrl: './dashboard-hero.html',
    styleUrl: './dashboard-hero.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardHero {
    readonly survived = input.required<number>();
    readonly total = input.required<number>();
    readonly stages = input.required<readonly FunnelStage[]>();
    /** What the run on screen extracted, or null when no run is known. */
    readonly runExtracted = input<number | null>(null);
    /** When that run finished, already formatted for the reader, or null. */
    readonly finishedAt = input<string | null>(null);

    /** A run is known and it brought nothing: the line that must not be a number. */
    protected readonly quiet = computed(() => this.runExtracted() === 0);

    protected readonly morning = computed<{readonly key: string; readonly params: Record<string, unknown>}>(() => {
        const extracted = this.runExtracted();
        const when = this.finishedAt();
        if (extracted === null) {
            return {key: 'dashboard.heroNoRun', params: {}};
        }
        if (extracted === 0) {
            return {key: 'dashboard.heroQuiet', params: {when: when ?? ''}};
        }
        return {key: 'dashboard.heroMorning', params: {count: extracted, when: when ?? ''}};
    });
}
