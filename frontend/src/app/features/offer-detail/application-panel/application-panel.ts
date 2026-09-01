import { ChangeDetectionStrategy, Component, computed, input, linkedSignal, output } from '@angular/core';
import {
  ApplicationEvent,
  ApplicationStatus,
  ApplicationUpdate,
  ApplicationView,
} from '@core/model/application';
import { PickerOption, StatusPicker } from '@shared/status-picker/status-picker';

/**
 * Where the decision to apply is actually made — right after reading the ad — so the
 * control that records it belongs here as well as on the board.
 *
 * The draft is a `linkedSignal` of the row rather than a copy taken once: the store
 * replaces the row with the server's answer after every save, which resets the form to
 * what was actually stored instead of leaving the operator looking at what they typed.
 */
@Component({
  selector: 'lg-application-panel',
  imports: [StatusPicker],
  templateUrl: './application-panel.html',
  styleUrl: './application-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ApplicationPanel {
  readonly application = input.required<ApplicationView>();
  readonly statusChoices = input.required<readonly PickerOption[]>();
  readonly history = input<readonly ApplicationEvent[]>([]);
  readonly saving = input(false);
  readonly changed = output<ApplicationUpdate>();

  protected readonly status = linkedSignal(() => this.application().status as string);
  protected readonly sentOn = linkedSignal(() => this.application().sentOn ?? '');
  protected readonly followUpOn = linkedSignal(() => this.application().followUpOn ?? '');
  protected readonly note = linkedSignal(() => this.application().note ?? '');

  protected readonly dirty = computed(() => {
    const current = this.application();
    return (
      this.status() !== current.status ||
      this.sentOn() !== (current.sentOn ?? '') ||
      this.followUpOn() !== (current.followUpOn ?? '') ||
      this.note() !== (current.note ?? '')
    );
  });

  /** The instant carries a time nobody entered; the history is a list of days. */
  protected day(recordedAt: string): string {
    return recordedAt.slice(0, 10);
  }

  protected setSentOn(event: Event): void {
    this.sentOn.set((event.target as HTMLInputElement).value);
  }

  protected setFollowUpOn(event: Event): void {
    this.followUpOn.set((event.target as HTMLInputElement).value);
  }

  protected setNote(event: Event): void {
    this.note.set((event.target as HTMLTextAreaElement).value);
  }

  protected save(): void {
    const current = this.application();
    const followUp = this.followUpOn();
    this.changed.emit({
      status: this.status() as ApplicationStatus,
      sentOn: this.sentOn() === '' ? null : this.sentOn(),
      followUpOn: followUp === '' ? null : followUp,
      // Emptying the field is the one thing a null date cannot express on its own.
      clearFollowUp: followUp === '' && current.followUpOn !== null,
      note: this.note() === '' ? null : this.note(),
    });
  }
}
