import {ChangeDetectionStrategy, Component, computed, effect, ElementRef, inject, signal, viewChild} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {injectDispatch} from '@ngrx/signals/events';
import {catchError, debounceTime, map, of, Subject, switchMap} from 'rxjs';
import {ShortlistApi} from '@core/api/shortlist.api';
import {ChatContextItem, MAX_PINNED_OFFERS, pinnedOfferCount, QUESTION_MAX_LENGTH, questionFits} from '@core/model/chat';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {chatEvents} from '@core/store/chat.events';
import {ChatStore} from '@core/store/chat.store';
import {DayPipe} from '@shared/date/day.pipe';
import {Icon} from '@shared/icon/icon';
import {ChatChips} from '../chat-context/chat-chips';

/** The `@` listbox's rows: six fit above the composer at every width. */
const MENTION_ROWS = 6;
/** How long the `@` search waits for the typing to pause before it asks the server. */
const MENTION_DEBOUNCE_MS = 150;
/** The pinned-offer count from which the chip row says how close the limit is. */
const COUNT_FROM = 8;

/** An `@query` being typed: where its `@` stands in the draft and the words after it. */
interface Mention {
    readonly start: number;
    readonly query: string;
}

/**
 * The `@query` the caret stands at the end of, or null: an `@` at the start of the draft or after
 * a space, followed by at least one character and no space — a space right after it closes it.
 */
function mentionAt(text: string, caret: number): Mention | null {
    const before = text.slice(0, caret);
    const match = /(^|\s)@(\S+)$/.exec(before);
    if (match === null) return null;
    return {start: before.length - match[2].length - 1, query: match[2]};
}

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
    imports: [ChatChips, DayPipe, Icon, TranslocoPipe],
    templateUrl: './chat-composer.html',
    styleUrl: './chat-composer.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatComposer {
    protected readonly store = inject(ChatStore);
    private readonly dispatch = injectDispatch(chatEvents);
    private readonly transloco = inject(TranslocoService);
    private readonly offers = inject(ShortlistApi);
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
    /**
     * Removable wherever a conversation is open: before it exists the pins are only the URL's, and a
     * stored conversation's change through `PUT …/context` (ISC-451).
     */
    protected readonly removable = computed(() => this.store.view() === 'new' || this.store.view() === 'conversation');
    protected readonly announcement = signal('');
    private announced: number | null = null;

    /** The offers pinned now; from eight on the chip row counts them against the ten (ISC-453). */
    protected readonly offerCount = computed(() => pinnedOfferCount(this.store.contextItems()));
    protected readonly showCount = computed(() => this.offerCount() >= COUNT_FROM);
    protected readonly maxOffers = MAX_PINNED_OFFERS;

    /**
     * The `@` search (ISC-453): the `@query` at the caret, what the offers endpoint found for it —
     * null until it answered, so "nothing matches" is never said before it was asked — and the row
     * the arrows stand on. The textarea is the combobox; the listbox opens upward, because a soft
     * keyboard takes the space below.
     */
    protected readonly mention = signal<Mention | null>(null);
    protected readonly found = signal<readonly ShortlistEntry[] | null>(null);
    protected readonly active = signal(0);
    protected readonly mentionOpen = computed(() => this.mention() !== null);
    protected readonly activeId = computed(() => {
        const rows = this.found();
        return this.mentionOpen() && rows !== null && rows.length > 0 ? this.optionId(rows[this.active()]) : null;
    });
    private readonly searches = new Subject<string>();

    constructor() {
        this.searches
            .pipe(
                debounceTime(MENTION_DEBOUNCE_MS),
                switchMap((query) =>
                    this.offers.search(query).pipe(
                        map((page) => ({query, rows: page.entries.slice(0, MENTION_ROWS)})),
                        // A search that failed finds nothing; the question can still be sent.
                        catchError(() => of({query, rows: [] as ShortlistEntry[]})),
                    ),
                ),
                takeUntilDestroyed(),
            )
            .subscribe(({query, rows}) => {
                if (this.mention()?.query !== query) return;
                this.found.set(rows);
                this.active.set(0);
            });

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
        this.findMention(field);
    }

    protected onKey(event: KeyboardEvent): void {
        if (this.mentionOpen() && !event.isComposing && this.onMentionKey(event)) return;
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

    /** Leaving the field closes the listbox; a row's own press keeps the focus, so it is not a blur. */
    protected closeMention(): void {
        this.mention.set(null);
        this.found.set(null);
    }

    protected optionId(entry: ShortlistEntry): string {
        return `lg-chat-mention-${entry.offer.id}`;
    }

    /**
     * A row picked (ISC-453): the offer pinned like any other, and the `@query` taken out of the draft.
     * An eleventh offer is refused by the store, which says why under the chips; the text then stays,
     * so nothing typed is lost to a pin that did not happen.
     */
    protected pick(entry: ShortlistEntry): void {
        const mention = this.mention();
        this.closeMention();
        this.dispatch.contextPinned({kind: 'OFFER', offerId: entry.offer.id});
        if (mention === null || this.store.contextRefused()) return;
        const draft = this.draft();
        const next = draft.slice(0, mention.start) + draft.slice(mention.start + 1 + mention.query.length);
        this.draft.set(next);
        const field = this.input()?.nativeElement;
        if (field) {
            field.value = next;
            field.setSelectionRange(mention.start, mention.start);
            this.grow(field);
        }
    }

    /** ↑/↓ move, Enter or Tab pick, Escape closes and keeps the typed text; anything else types on. */
    private onMentionKey(event: KeyboardEvent): boolean {
        const rows = this.found() ?? [];
        switch (event.key) {
            case 'ArrowDown':
            case 'ArrowUp': {
                event.preventDefault();
                if (rows.length === 0) return true;
                const step = event.key === 'ArrowDown' ? 1 : -1;
                this.active.update((index) => (index + step + rows.length) % rows.length);
                document.getElementById(this.activeId() ?? '')?.scrollIntoView?.({block: 'nearest'});
                return true;
            }
            case 'Enter':
            case 'Tab': {
                if (rows.length === 0 || event.shiftKey) return false;
                event.preventDefault();
                this.pick(rows[this.active()]);
                return true;
            }
            case 'Escape':
                // The listbox closes; the sheet it sits in stays open.
                event.preventDefault();
                event.stopPropagation();
                this.closeMention();
                return true;
            default:
                return false;
        }
    }

    private findMention(field: HTMLTextAreaElement): void {
        const mention = mentionAt(field.value, field.selectionStart ?? field.value.length);
        if (mention === null) {
            this.closeMention();
            return;
        }
        if (mention.query !== this.mention()?.query) this.found.set(null);
        this.mention.set(mention);
        this.searches.next(mention.query);
    }

    /** A chip's ✕: out of the URL, and out of the stored conversation when there is one (ISC-451). */
    protected unpin(item: ChatContextItem): void {
        this.dispatch.contextRemoved(item);
    }

    /** The fallback for a browser without `field-sizing`: one line up to the CSS maximum, then it scrolls. */
    private grow(field: HTMLTextAreaElement): void {
        if (GROWS_ITSELF) return;
        field.style.height = 'auto';
        if (field.scrollHeight > 0) field.style.height = `${field.scrollHeight}px`;
    }
}
