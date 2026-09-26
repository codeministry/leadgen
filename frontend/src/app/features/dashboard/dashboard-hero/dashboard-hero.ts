import {ChangeDetectionStrategy, Component, computed, input} from '@angular/core';
import {RouterLink} from '@angular/router';
import {TranslocoPipe} from '@jsverse/transloco';
import {FunnelStage} from '@shared/funnel-rail/funnel-stage';
import {Icon} from '@shared/icon/icon';
import {Sieve} from '../sieve/sieve';

/**
 * The control room's first read, on its own dark stage: one sentence with one number in it,
 * the sieve beside it, one button.
 *
 * <p>The number is the standing shortlist — what survived the filter — in the signal, at
 * display size, inside a sentence that names the archive it came out of. The strong matches
 * among them are a second, quieter line. What the last run brought is a third, and it says in
 * words why its count is larger than the archive's: the same listing arrives in more than one
 * newsletter, and a repeat is read but not added. A run that extracted nothing says so in
 * words, and the survivor count is never presented as this morning's catch (spec 006,
 * ISC-265). The number chain the compact funnel rail used to show beside all this is gone: the
 * hard filter panel next to the hero says the same thing in full, and the sieve draws it.
 */
@Component({
    selector: 'lg-dashboard-hero',
    imports: [Icon, RouterLink, Sieve, TranslocoPipe],
    templateUrl: './dashboard-hero.html',
    styleUrl: './dashboard-hero.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardHero {
    readonly survived = input.required<number>();
    readonly total = input.required<number>();
    readonly stages = input.required<readonly FunnelStage[]>();
    /** The strong matches among the survivors, or null while the scores have not loaded. */
    readonly strong = input<number | null>(null);
    /** What the run on screen extracted, or null when no run is known. */
    readonly runExtracted = input<number | null>(null);
    /** What that run wrote: lower than extracted by the listings it saw twice. */
    readonly runWritten = input<number | null>(null);
    /** When that run finished, already formatted for the reader, or null. */
    readonly finishedAt = input<string | null>(null);

    /** A run is known and it brought nothing: the line that must not be a number. */
    protected readonly quiet = computed(() => this.runExtracted() === 0);

    protected readonly morning = computed<{readonly key: string; readonly params: Record<string, unknown>}>(() => {
        const extracted = this.runExtracted();
        const when = this.finishedAt() ?? '';
        if (extracted === null) {
            return {key: 'dashboard.heroNoRun', params: {}};
        }
        if (extracted === 0) {
            return {key: 'dashboard.heroQuiet', params: {when}};
        }
        const repeats = Math.max(0, extracted - (this.runWritten() ?? extracted));
        return {key: 'dashboard.heroMorning', params: {count: extracted, repeats, when}};
    });
}
