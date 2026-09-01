import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

export interface PickerOption {
  readonly value: string;
  readonly label: string;
}

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
  /** Read by screen readers only; on a card the surrounding text is the visible label. */
  readonly label = input('Status');
  readonly disabled = input(false);
  readonly picked = output<string>();

  protected readonly id = `lg-status-${nextId++}`;

  protected onChange(event: Event): void {
    this.picked.emit((event.target as HTMLSelectElement).value);
  }
}
