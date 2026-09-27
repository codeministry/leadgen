import {ChangeDetectionStrategy, Component, computed, effect, ElementRef, inject, signal, viewChild} from '@angular/core';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {injectDispatch} from '@ngrx/signals/events';
import {QUESTION_MAX_LENGTH, questionFits} from '@core/model/chat';
import {chatEvents} from '@core/store/chat.events';
import {ChatStore} from '@core/store/chat.store';
import {Icon} from '@shared/icon/icon';

/** Whether the browser grows a textarea with its content by itself; otherwise a script does. */
const GROWS_ITSELF = typeof CSS !== 'undefined' && typeof CSS.supports === 'function' && CSS.supports('field-sizing', 'content');

/**
 * The chat's input shelf (ISC-436): the question, the pinned offer's chip, send, and the one
 * polite announcement a finished answer gets.
 *
 * <p>**Enter sends, Shift+Enter breaks the line**, and Enter during an input method's composition
 * does neither: a Japanese or Chinese reader confirms a candidate with Enter, and that must not
 * post a half-typed question that costs a model call.
 *
 * <p>**Announced once, never per token.** The thread carries no `aria-live` and no `role="log"`,
 * either of which would read every chunk aloud. This region stays empty while a turn streams and
 * receives one sentence when it ends with its sources — and it is emptied as the next turn starts,
 * so the same sentence twice is still heard twice.
 */
@Component({
    selector: 'lg-chat-composer',
    imports: [Icon, TranslocoPipe],
    templateUrl: './chat-composer.html',
    styleUrl: './chat-composer.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatComposer {
    protected readonly store = inject(ChatStore);
    private readonly dispatch = injectDispatch(chatEvents);
    private readonly transloco = inject(TranslocoService);
    private readonly input = viewChild<ElementRef<HTMLTextAreaElement>>('input');

    protected readonly draft = signal('');
    /**
     * Whether this conversation's own turn is streaming. `store.streaming()` is global: a turn still
     * being written in conversation A must not turn B's send into a stop that stops A.
     */
    protected readonly streamingHere = computed(() => this.store.liveTurn()?.state === 'STREAMING');
    /** A missing or a still-loading conversation takes no question (`ChatStore.canAsk`). */
    protected readonly closed = computed(() => !this.store.canAsk());
    /**
     * Global on purpose: the store runs one turn at a time, so send waits for a stream open anywhere —
     * including a deleted conversation's turn that is still being stopped (fix 3F-4).
     */
    protected readonly canSend = computed(() => questionFits(this.draft().trim()) && this.store.takesQuestion());
    protected readonly maxLength = QUESTION_MAX_LENGTH;
    /** The count shows from nine tenths of the limit on: below it, it is noise. */
    protected readonly count = computed(() => {
        const length = this.draft().length;
        return length >= QUESTION_MAX_LENGTH * 0.9 ? length : null;
    });
    /** Send, or stop while this conversation's turn streams: one button, two names. */
    protected readonly actionKey = computed(() => (this.streamingHere() ? 'chat.stop' : 'chat.send'));
    /** The offer a new conversation starts pinned to; after the first send it is part of the turn. */
    protected readonly pinned = computed(() => (this.store.view() === 'new' ? this.store.pinnedOfferId() : null));
    protected readonly announcement = signal('');
    private announced: number | null = null;

    constructor() {
        effect(() => {
            const live = this.store.live();
            if (live === null) return;
            if (live.state === 'STREAMING') {
                this.announcement.set('');
            } else if (live.state === 'DONE' && live.turnId !== null && live.turnId !== this.announced) {
                this.announced = live.turnId;
                this.announcement.set(this.transloco.translate('chat.announce.done', {count: live.sources.length}));
            }
        });
    }

    protected onInput(event: Event): void {
        const field = event.target as HTMLTextAreaElement;
        this.draft.set(field.value);
        this.grow(field);
    }

    protected onKey(event: KeyboardEvent): void {
        if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return;
        event.preventDefault();
        this.send();
    }

    /**
     * Stop keeps the stream open: the server cancels the model call and ends the turn with
     * `done {STOPPED}`, which is what hands the button back to send (ISC-439).
     */
    protected act(): void {
        if (this.streamingHere()) this.dispatch.stopRequested();
        else this.send();
    }

    /**
     * The draft is let go only for a question the store takes: one it would refuse — a stream still
     * open, a question over the server's limit — stays in the field rather than vanishing unsent.
     */
    protected send(): void {
        const question = this.draft().trim();
        if (!questionFits(question) || !this.store.takesQuestion()) return;
        this.dispatch.asked(question);
        if (this.store.live()?.question !== question || !this.store.streamOpen()) return;
        this.draft.set('');
        const field = this.input()?.nativeElement;
        if (field) {
            field.value = '';
            this.grow(field);
        }
    }

    /** Unpinning is a fresh `new` without the offer; `?chat` already says `new`, so nothing moves. */
    protected unpin(): void {
        this.dispatch.newRequested({pinnedOfferId: null});
    }

    /** The fallback for a browser without `field-sizing`: one line up to the CSS maximum, then it scrolls. */
    private grow(field: HTMLTextAreaElement): void {
        if (GROWS_ITSELF) return;
        field.style.height = 'auto';
        if (field.scrollHeight > 0) field.style.height = `${field.scrollHeight}px`;
    }
}
