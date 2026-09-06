import {ChangeDetectionStrategy, Component, computed, input, output} from '@angular/core';

export interface PickerOption {
    readonly value: string;
    readonly label: string;
}

/**
 * Spelled out rather than assembled, because Tailwind scans source *text* for class
 * names: `'select-' + size()` would leave the variant in the DOM and never in the CSS.
 */
const SELECT_CLASS = {
    xs: 'select select-xs type-caption picker',
    sm: 'select select-sm type-caption picker picker-sm',
} as const;

export type PickerSize = keyof typeof SELECT_CLASS;

let nextId = 0;

/**
 * A plain select, on purpose.
 *
 * Dragging a card between lanes is the nicer gesture and the worse control: it needs a
 * pointer, a screen wide enough to show the target lane, and a steady hand. This is the
 * half of the loop the tool cannot observe, so it is updated wherever the operator
 * happens to be — often on a phone, right after sending the mail.
 *
 * It knows nothing about applications. `shared/` sits below `core/`, so the eleven states
 * arrive as options from the feature that has them.
 */
@Component({
    selector: 'lg-status-picker',
    templateUrl: './status-picker.html',
    styleUrl: './status-picker.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusPicker {
    readonly value = input.required<string>();
    readonly options = input.required<readonly PickerOption[]>();
    /** The accessible name. On a card the surrounding text is the visible label, in a form
     * row the label itself is — see `labelHidden`. */
    readonly label = input('Status');
    /** Hidden on a card, shown in a form row, where the fields beside it carry theirs. */
    readonly labelHidden = input(true);
    /** `sm` matches the `input input-sm` height a form row is built from. */
    readonly size = input<PickerSize>('xs');
    readonly disabled = input(false);
    readonly picked = output<string>();

    protected readonly id = `lg-status-${nextId++}`;
    protected readonly selectClass = computed(() => SELECT_CLASS[this.size()]);
    protected readonly labelClass = computed(() =>
        this.labelHidden() ? 'sr-only' : 'type-caption text-muted',
    );

    protected onChange(event: Event): void {
        this.picked.emit((event.target as HTMLSelectElement).value);
    }
}
