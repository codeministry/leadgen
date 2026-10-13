import {ChangeDetectionStrategy, Component, computed, DestroyRef, ElementRef, inject, input, signal, viewChild} from '@angular/core';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {toSignal} from '@angular/core/rxjs-interop';
import {injectDispatch} from '@ngrx/signals/events';
import {ChatConnectionName} from '@core/model/chat';
import {chatEvents} from '@core/store/chat.events';
import {ChatStore} from '@core/store/chat.store';
import {LivingMark, LivingMarkFrame} from '@shared/living-mark/living-mark';
import {formatUsd} from '@shared/money/usd';
import {track, unplace} from '../popover-placement';
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
 * `popover="manual"` in the top layer — the thread scrolls and clips, the top layer does neither — placed
 * below the ring, or above it where the room below is too short, by the picker's helper
 * (`popover-placement.ts`, ISC-524): clamped inside the visual viewport and re-placed while open, so a
 * phone's edge does not cut it and a scrolled thread does not strand it. It holds no focusable element,
 * so the ring stays one tab stop.
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
    /** The chat's status, asked through the store on each open; null until the first read answers. */
    protected readonly chat = this.store.status;
    protected readonly chatFailed = this.store.statusFailed;
    protected readonly chatLoading = this.store.statusLoading;
    /**
     * The second connection's calls of its own ceiling (ISC-510), or null without a second connection.
     * A ceiling of 0 is still a ceiling and still shown, so the test is for absence, not falsiness.
     */
    protected readonly second = computed(() => {
        const limit = this.chat()?.secondLimit;
        if (limit === null || limit === undefined) return null;
        return {used: this.chat()?.secondCalls ?? 0, limit};
    });

    private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');
    private readonly panel = viewChild.required<ElementRef<HTMLElement>>('panel');
    private readonly lang = toSignal(this.transloco.langChanges$, {initialValue: this.transloco.getActiveLang()});
    private timer: ReturnType<typeof setTimeout> | undefined;
    /** Stops the placement that follows the ring while the popover is open; null while it is closed. */
    private stopTracking: (() => void) | null = null;

    constructor() {
        this.destroyRef.onDestroy(() => {
            clearTimeout(this.timer);
            this.stopTracking?.();
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

    /**
     * "{in} in · {out} out" with the reader's digit grouping; null for a turn without tokens (ISC-525).
     * "Usage not reported" for a turn whose provider reported none: the server stores it as no tokens
     * either way and no cost, which a free turn — its cost "0" — never is.
     */
    protected readonly tokens = computed(() => {
        const lang = this.lang();
        const turn = this.turn();
        const tokens = turn?.tokens ?? null;
        if (tokens === null) return null;
        if (unreported(turn)) return this.transloco.translate('chat.status.usageNotReported');
        const digits = new Intl.NumberFormat(lang);
        return this.transloco.translate('chat.status.tokensValue', {in: digits.format(tokens.input), out: digits.format(tokens.output)});
    });

    /**
     * What the turn cost (ISC-525): an amount is the USD amount, and none beside the tokens is "price
     * unknown" (a billed model without a price, ISC-519) — unless the server marked the usage as not
     * fully reported, which says so instead (spec 026). A zero amount is "free" only when the catalog
     * says the turn's connection is not billed; on a billed one — a model priced at 0, a cost rounded
     * to 0 — and while `/models` has not answered it is the amount, "$0.00": free is never claimed
     * without knowing. Shown only beside the tokens, so a turn stored before either was kept shows neither, and not beside
     * a usage the provider never reported, which has nothing to be priced.
     */
    protected readonly cost = computed(() => {
        const lang = this.lang();
        const turn = this.turn();
        if (turn === null || turn.tokens === null || unreported(turn)) return null;
        if (turn.usageIncomplete) return {kind: 'incomplete', text: this.transloco.translate('chat.status.usageIncomplete')} as const;
        if (turn.costUsd === null) return {kind: 'unknown', text: this.transloco.translate('chat.model.picker.priceUnknown')} as const;
        if (this.unbilled(turn.connection) && turn.costUsd.trim() !== '' && Number(turn.costUsd) === 0) {
            return {kind: 'free', text: this.transloco.translate('chat.model.picker.free')} as const;
        }
        return {kind: 'amount', text: formatUsd(turn.costUsd, lang)} as const;
    });

    /** Whether the catalog has answered and lists the connection as not billed; false while it is unknown. */
    private unbilled(connection: ChatConnectionName): boolean {
        return this.store.models() !== null && !this.store.billedConnections().includes(connection);
    }

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
        const trigger = this.trigger().nativeElement;
        const panel = this.panel().nativeElement;
        // Nothing an earlier open wrote stays: the side is chosen from the panel's own height, not a capped one.
        unplace(panel);
        panel.showPopover?.();
        // Shown first, so the side is chosen from the height the panel has; placed before the first paint.
        this.stopTracking = track(trigger, panel, {side: this.sideFor(trigger, panel), align: 'start'});
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
        this.stopTracking?.();
        this.stopTracking = null;
        const panel = this.panel().nativeElement;
        if (panel.matches?.(':popover-open')) panel.hidePopover?.();
        this.open.set(false);
    }

    /**
     * Below the ring, as a reader's eye goes on down the answer, unless the panel does not fit there and
     * there is more room above — the ring of the last answer often sits low in the thread. Chosen once per
     * open, so the panel does not jump sides while the thread scrolls under it.
     */
    private sideFor(trigger: HTMLElement, panel: HTMLElement): 'above' | 'below' {
        const view = window.visualViewport;
        const top = view?.offsetTop ?? 0;
        const box = trigger.getBoundingClientRect();
        const below = top + (view?.height ?? window.innerHeight) - box.bottom;
        const above = box.top - top;
        return panel.getBoundingClientRect().height + 32 <= below || below >= above ? 'below' : 'above';
    }

    private loadChat(): void {
        this.dispatch.statusRequested();
    }
}

/**
 * A billed turn whose provider reported no usage: the server stores zero tokens each way and no cost
 * (ISC-518, ISC-519), and marks it `usageIncomplete` (spec 026). Zero tokens beside a cost of "0" is
 * a free turn and still says its numbers. Nothing reported is the extreme of not fully reported, so
 * it says so on the tokens row and shows no cost row.
 */
function unreported(turn: TurnStatus | null): boolean {
    return turn?.tokens != null && turn.tokens.input === 0 && turn.tokens.output === 0 && turn.costUsd === null;
}
