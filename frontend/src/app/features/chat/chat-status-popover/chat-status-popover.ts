import {ChangeDetectionStrategy, Component, computed, DestroyRef, ElementRef, inject, input, signal, viewChild} from '@angular/core';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {toSignal} from '@angular/core/rxjs-interop';
import {injectDispatch} from '@ngrx/signals/events';
import {chatEvents} from '@core/store/chat.events';
import {ChatStore} from '@core/store/chat.store';
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
    host: {'[class.is-open]': 'open()'},
})
export class ChatStatusPopover {
    private readonly store = inject(ChatStore);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly dispatch = injectDispatch(chatEvents);
    private readonly transloco = inject(TranslocoService);
    private readonly destroyRef = inject(DestroyRef);

    readonly frame = input<LivingMarkFrame>('rest');
    readonly size = input(20);
    /** The turn to describe; null describes the chat itself (the empty chat's plate). */
    readonly turn = input<TurnStatus | null>(null);

    protected readonly panelId = `lg-chat-status-${++instances}`;
    protected readonly open = signal(false);
    /** The chat's status, asked through the store on each open; null while it loads. */
    protected readonly chat = this.store.status;
    protected readonly chatFailed = this.store.statusFailed;

    private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');
    private readonly panel = viewChild.required<ElementRef<HTMLElement>>('panel');
    private readonly lang = toSignal(this.transloco.langChanges$, {initialValue: this.transloco.getActiveLang()});
    private timer: ReturnType<typeof setTimeout> | undefined;

    constructor() {
        this.destroyRef.onDestroy(() => {
            clearTimeout(this.timer);
            document.removeEventListener('keydown', this.escape, true);
            document.removeEventListener('pointerdown', this.outside, true);
        });
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

    /** The pointer that pressed the ring last: a tap toggles, a mouse's click leaves hover in charge. */
    protected pointerType = '';

    /** Hover is a mouse's and a pen's. A tap brings enter and leave around itself and must not close what it opened. */
    protected enter(event?: PointerEvent): void {
        if (event?.pointerType === 'touch') return;
        clearTimeout(this.timer);
        this.timer = setTimeout(() => this.show(), HOVER_INTENT_MS);
    }

    protected leave(event?: PointerEvent): void {
        if (event?.pointerType === 'touch') return;
        clearTimeout(this.timer);
        this.timer = setTimeout(() => this.hide(), HOVER_INTENT_MS);
    }

    /**
     * A touch screen has no hover and iOS gives a tapped button no focus, so a tap opens and closes it.
     * The tap is forgotten with its own click, so a later keyboard focus opens the popover again.
     */
    protected tap(): void {
        if (this.pointerType !== 'touch') return;
        this.pointerType = '';
        if (this.open()) this.hide();
        else this.show();
    }

    /**
     * Keyboard focus opens it. The focus a tap brings with it on Chromium (Android) arrives before the
     * tap's click and is left to the tap: opened here, the click would read it as open and close it.
     */
    protected focused(): void {
        if (this.pointerType === 'touch') return;
        this.show();
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
        document.addEventListener('keydown', this.escape, true);
        document.addEventListener('pointerdown', this.outside, true);
        if (this.turn() === null) this.loadChat();
    }

    /**
     * Escape closes an open popover. Listened for in the capture phase on the document, only while
     * this popover is open, so it closes wherever focus is. It keeps the key only when focus is on
     * the ring itself: then neither the docked drawer (which closes on a bubbling Escape) nor the
     * modal sheet (whose `<dialog>` closes on the key's default action) sees it. With focus
     * elsewhere the key goes on to what handles it there — a rename, select mode, a dialog — so one
     * press does both rather than needing a second.
     */
    private readonly escape = (event: KeyboardEvent): void => {
        if (event.key !== 'Escape' || !this.open()) return;
        if (this.host.nativeElement.contains(this.host.nativeElement.ownerDocument.activeElement)) {
            event.preventDefault();
            event.stopPropagation();
        }
        this.hide();
    };

    /** A tap or click outside the ring and its panel closes it: the touch path has no leave to do so. */
    private readonly outside = (event: PointerEvent): void => {
        const target = event.target as Node | null;
        if (target && (this.trigger().nativeElement.contains(target) || this.panel().nativeElement.contains(target))) return;
        this.hide();
    };

    hide(): void {
        clearTimeout(this.timer);
        if (!this.open()) return;
        document.removeEventListener('keydown', this.escape, true);
        document.removeEventListener('pointerdown', this.outside, true);
        const panel = this.panel().nativeElement;
        if (panel.matches?.(':popover-open')) panel.hidePopover?.();
        this.open.set(false);
    }

    private loadChat(): void {
        this.dispatch.statusRequested();
    }
}
