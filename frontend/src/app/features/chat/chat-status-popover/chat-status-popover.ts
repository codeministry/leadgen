import {ChangeDetectionStrategy, Component, computed, DestroyRef, ElementRef, inject, input, signal, viewChild} from '@angular/core';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {toSignal} from '@angular/core/rxjs-interop';
import {ChatApi} from '@core/api/chat.api';
import {ChatStatus} from '@core/model/chat';
import {LivingMark, LivingMarkFrame} from '@shared/living-mark/living-mark';
import {TurnStatus} from './turn-status';

let instances = 0;

/** A pointer passing over the ring on its way to the answer opens nothing; one that rests does. */
const HOVER_INTENT_MS = 120;

/**
 * The living mark as a control that says what it stands for (spec 022, ISC-474 to ISC-476): beside
 * an answer, what happened to that turn; on the empty chat's plate, the chat's own state.
 *
 * <p>Hover (after a short intent) and keyboard focus open the popover; leaving both the ring and the
 * popover, blur and Escape close it. While it is open the mark turns once, through the
 * `--lg-living-mark-turn` the mark reads, so `shared/` names no chat concept. The popover is a
 * `popover="manual"` in the top layer, placed beside the ring from its box — the thread scrolls and
 * clips, the top layer does neither. It holds no focusable element, so the ring stays one tab stop.
 *
 * <p>Every word comes from the catalogs; the model's name is a value, never a sentence.
 */
@Component({
    selector: 'lg-chat-status',
    imports: [LivingMark, TranslocoPipe],
    templateUrl: './chat-status-popover.html',
    styleUrl: './chat-status-popover.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: {'[class.is-open]': 'open()', '(document:keydown.escape)': 'hide()'},
})
export class ChatStatusPopover {
    private readonly api = inject(ChatApi);
    private readonly transloco = inject(TranslocoService);
    private readonly destroyRef = inject(DestroyRef);

    readonly frame = input<LivingMarkFrame>('rest');
    readonly size = input(20);
    /** The turn to describe; null describes the chat itself (the empty chat's plate). */
    readonly turn = input<TurnStatus | null>(null);

    protected readonly panelId = `lg-chat-status-${++instances}`;
    protected readonly open = signal(false);
    /** The chat's status, asked on each open; null while it loads. */
    protected readonly chat = signal<ChatStatus | null>(null);
    protected readonly chatFailed = signal(false);

    private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');
    private readonly panel = viewChild.required<ElementRef<HTMLElement>>('panel');
    private readonly lang = toSignal(this.transloco.langChanges$, {initialValue: this.transloco.getActiveLang()});
    private timer: ReturnType<typeof setTimeout> | undefined;

    constructor() {
        this.destroyRef.onDestroy(() => clearTimeout(this.timer));
    }

    /** The ring's name: the turn's state, or "Chat status" on the plate. */
    protected readonly label = computed(() => {
        this.lang();
        const turn = this.turn();
        if (turn === null) return this.transloco.translate('chat.status.chatLabel');
        return this.transloco.translate('chat.status.turnLabel', {state: this.transloco.translate(`chat.status.state.${turn.kind}`)});
    });

    /** A duration in the reader's language: tenths of a second under a minute, minutes and seconds above. */
    protected readonly duration = computed(() => {
        const lang = this.lang();
        const ms = this.turn()?.durationMs ?? null;
        if (ms === null) return null;
        if (ms < 60_000) {
            const n = new Intl.NumberFormat(lang, {maximumFractionDigits: 1, minimumFractionDigits: 1}).format(ms / 1000);
            return this.transloco.translate('chat.status.seconds', {n});
        }
        const total = Math.round(ms / 1000);
        return this.transloco.translate('chat.status.minutes', {m: Math.floor(total / 60), s: total % 60});
    });

    protected enter(): void {
        clearTimeout(this.timer);
        this.timer = setTimeout(() => this.show(), HOVER_INTENT_MS);
    }

    protected leave(): void {
        clearTimeout(this.timer);
        this.timer = setTimeout(() => this.hide(), HOVER_INTENT_MS);
    }

    protected stay(): void {
        clearTimeout(this.timer);
    }

    protected show(): void {
        clearTimeout(this.timer);
        if (this.open()) return;
        const box = this.trigger().nativeElement.getBoundingClientRect();
        const panel = this.panel().nativeElement;
        panel.style.setProperty('--lg-anchor-x', `${Math.round(box.right + 8)}px`);
        panel.style.setProperty('--lg-anchor-y', `${Math.round(box.top)}px`);
        panel.showPopover?.();
        this.open.set(true);
        if (this.turn() === null) this.loadChat();
    }

    hide(): void {
        clearTimeout(this.timer);
        if (!this.open()) return;
        const panel = this.panel().nativeElement;
        if (panel.matches?.(':popover-open')) panel.hidePopover?.();
        this.open.set(false);
    }

    private loadChat(): void {
        this.chat.set(null);
        this.chatFailed.set(false);
        this.api.status().subscribe({
            next: (status) => this.chat.set(status),
            error: () => this.chatFailed.set(true),
        });
    }
}
