import {ChangeDetectionStrategy, Component, computed, input} from '@angular/core';
import {Icon} from '@shared/icon/icon';
import {LgIconName} from '@shared/icon/lucide-icons';
import {Tone, TONE_CLASS} from '@shared/tone/tone';

@Component({
    selector: 'lg-stat-tile',
    imports: [Icon],
    templateUrl: './stat-tile.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatTile {
    readonly label = input.required<string>();
    readonly value = input.required<string | number>();
    readonly unit = input<string | null>(null);
    readonly hint = input<string | null>(null);
    /** `accent` is for the one figure on the screen that the morning is about. */
    readonly emphasis = input(false);
    /** An icon in a tinted chip beside the label. Decorative: the label carries the meaning. */
    readonly icon = input<LgIconName | null>(null);
    /**
     * What the value means, as colour: the chip, a glow out of the tile's corner, and the
     * value itself. Null keeps the tile as it always was.
     */
    readonly tone = input<Tone | null>(null);

    protected readonly toneClass = computed(() => {
        const tone = this.tone();
        return tone === null ? '' : `${TONE_CLASS[tone]} lg-tone-wash`;
    });
}
