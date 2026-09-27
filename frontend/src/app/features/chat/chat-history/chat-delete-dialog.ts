import {ChangeDetectionStrategy, Component, computed, ElementRef, inject, signal, viewChild} from '@angular/core';
import {TranslocoPipe} from '@jsverse/transloco';
import {injectDispatch} from '@ngrx/signals/events';
import {chatEvents} from '@core/store/chat.events';
import {ChatStore} from '@core/store/chat.store';

/** What a "Delete" asks with: the conversation, and where focus goes after a cancel or a delete. */
export interface DeleteAsk {
    readonly id: number;
    readonly title: string;
    /** The "⋯" that opened it: focus returns there on cancel. */
    readonly back: HTMLElement | null;
    /** The next row, or the one before when it was the last: focus goes there after a delete. */
    readonly next: HTMLElement | null;
}

/**
 * The delete confirmation (ISC-448): the app's native `<dialog>` opened with `showModal()`, the
 * primitive the shortlist's archive confirmation uses. It names the conversation, warns when its
 * answer is still being written, and focuses Cancel first, so the delete stays one deliberate Tab
 * away from Enter.
 *
 * <p>Optional-called throughout, like the shortlist's: jsdom's `HTMLDialogElement` has no
 * `showModal` and no `close`.
 */
@Component({
    selector: 'lg-chat-delete-dialog',
    imports: [TranslocoPipe],
    templateUrl: './chat-delete-dialog.html',
    styleUrl: './chat-delete-dialog.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatDeleteDialog {
    private readonly store = inject(ChatStore);
    private readonly dispatch = injectDispatch(chatEvents);
    private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

    protected readonly pending = signal<DeleteAsk | null>(null);
    /** Deleting stops a turn still being written (the server does it first); the dialog says so. */
    protected readonly streaming = computed(() => {
        const live = this.store.live();
        return live?.state === 'STREAMING' && live.conversationId === this.pending()?.id;
    });

    ask(what: DeleteAsk): void {
        this.pending.set(what);
        this.dialog().nativeElement.showModal?.();
    }

    protected cancel(): void {
        const back = this.pending()?.back ?? null;
        this.pending.set(null);
        this.dialog().nativeElement.close?.();
        back?.focus();
    }

    protected confirm(): void {
        const what = this.pending();
        if (what === null) return;
        this.dispatch.deleteRequested(what.id);
        this.pending.set(null);
        this.dialog().nativeElement.close?.();
        what.next?.focus();
    }
}
