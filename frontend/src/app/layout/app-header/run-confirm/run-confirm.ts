import {ChangeDetectionStrategy, Component, ElementRef, output, viewChild} from '@angular/core';
import {TranslocoPipe} from '@jsverse/transloco';

/**
 * The question before a run (spec 010, ISC-318).
 *
 * <p>A run reads the mailbox, calls the models and spends the call budget, and the button that
 * starts one sits in the header on every screen, one misplaced click from the settings gear.
 * So the button only asks; the run is started by `confirmed`, and cancel, Escape and the
 * backdrop start nothing.
 *
 * <p>A component of its own rather than a `<dialog>` inside the header's template: the
 * dialog's Start is a primary of its own, and the header already has its one — the run
 * button. Split this way, each template keeps the single filled button the tier guard holds
 * it to, with no allowance written for it.
 *
 * <p>Optional-called like every dialog in the app: jsdom implements `HTMLDialogElement`
 * without `showModal` and `close`, and an unguarded call takes down every spec that renders
 * the header.
 */
@Component({
    selector: 'lg-run-confirm',
    imports: [TranslocoPipe],
    templateUrl: './run-confirm.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunConfirm {
    /** The run is to go ahead. The dialog is already closed when this fires. */
    readonly confirmed = output<void>();

    private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
    private trigger: HTMLElement | null = null;

    /** `trigger` gets focus back when the dialog closes, however it closes. */
    open(trigger: HTMLElement): void {
        this.trigger = trigger;
        this.dialog().nativeElement.showModal?.();
    }

    protected cancel(event?: Event): void {
        // Escape arrives as `cancel` and then `close`; closing here and letting the browser
        // do nothing more keeps it one path in a browser and in jsdom alike.
        event?.preventDefault();
        this.dialog().nativeElement.close?.();
    }

    protected confirm(): void {
        this.dialog().nativeElement.close?.();
        this.confirmed.emit();
    }

    protected onClose(): void {
        this.trigger?.focus();
        this.trigger = null;
    }
}
