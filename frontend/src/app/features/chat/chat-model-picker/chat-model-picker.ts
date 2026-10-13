import {ChangeDetectionStrategy, Component, computed, effect, ElementRef, inject, OnDestroy, signal, untracked, viewChild} from '@angular/core';
import {NgTemplateOutlet} from '@angular/common';
import {toSignal} from '@angular/core/rxjs-interop';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {injectDispatch} from '@ngrx/signals/events';
import {ChatConnectionName, ChatConnectionSpend, ChatModel, ChatModelPrice, ChatPickReason, hasModelChoice, unpickableReason} from '@core/model/chat';
import {chatEvents} from '@core/store/chat.events';
import {ChatStore} from '@core/store/chat.store';
import {Icon} from '@shared/icon/icon';
import {formatUsd, formatUsdPrice} from '@shared/money/usd';
import {track} from '../popover-placement';

/** What a model costs, as a row shows it: nothing, not known, or a priced pair already written as USD. */
type RowPrice = {readonly kind: 'free'} | {readonly kind: 'unknown'} | {readonly kind: 'priced'; readonly input: string; readonly output: string};

/** One model row of the picker: its value as a pick, its name, whether its connection is billed, and its price. */
interface ModelOption {
    readonly value: string;
    readonly model: string;
    readonly billed: boolean;
    readonly isDefault: boolean;
    readonly price: RowPrice;
}

/**
 * One group of the picker: a connection, its options, and why they are disabled if they are — plus what
 * its head shows: the provider kind, the calls today against the ceiling, and the spend.
 */
interface ModelGroup {
    readonly name: ChatConnectionName;
    readonly labelKey: string;
    readonly reason: ChatPickReason | null;
    readonly options: readonly ModelOption[];
    readonly billed: boolean;
    readonly kind: string;
    readonly calls: {readonly used: number; readonly limit: number} | null;
    readonly spend: GroupSpend | null;
    /**
     * A connection the catalog no longer lists while its spend this month is still in the total: only its
     * head and that spend, no kind, no calls and no models, so every amount the foot adds up is shown.
     */
    readonly retired: boolean;
}

/**
 * A group's spend as its head shows it, written as USD, with the turns it leaves out by cause: a model without
 * a price, and usage the provider did not (fully) report.
 */
interface GroupSpend {
    readonly today: string;
    readonly month: string;
    readonly unpriced?: Uncounted;
    readonly unreported?: Uncounted;
}

/** One connection's spend, written as USD in the reader's language. */
function spendOf(spent: ChatConnectionSpend, lang: string): GroupSpend {
    return {
        today: formatUsd(spent.today, lang),
        month: formatUsd(spent.month, lang),
        unpriced: uncountedOf(spent.unpricedToday, spent.unpricedMonth),
        unreported: uncountedOf(spent.unreportedToday ?? 0, spent.unreportedMonth ?? 0),
    };
}

/** The turns one cause left out of a spend, today and this month (ISC-521). */
interface Uncounted {
    readonly today: number;
    readonly month: number;
}

/** The left-out turns worth a note: some today or some this month; undefined for none at all. */
function uncountedOf(today: number, month: number): Uncounted | undefined {
    return today + month > 0 ? {today, month} : undefined;
}

/** The catalog keys naming each connection: its configured role, never its host or vendor. */
const CONNECTION_KEYS: Record<ChatConnectionName, string> = {
    PRIMARY: 'chat.model.connection.PRIMARY',
    SECOND: 'chat.model.connection.SECOND',
};

/**
 * The option standing for the default when the catalog lists it nowhere (ISC-511): picking it sends no
 * pick. Holds no colon, so it is never read as a connection and a model.
 */
const DEFAULT_OPTION = 'default';

/** The reason key a spent day carries; any other reason is a connection that cannot be reached. */
const SPENT_KEY = 'chat.model.spent';

/** The rows the keys move between: an option its connection's reason does not disable. */
const ENABLED_ROW = '[role="option"]:not([aria-disabled="true"])';

/**
 * The default's own option: its label as a catalog key, whether the primary connection it runs on is
 * billed, and why that connection cannot be asked now — which disables the option, the way it disables
 * the connection's group — or null.
 */
interface DefaultOption {
    readonly key: string;
    readonly params: Readonly<Record<string, string>>;
    readonly billed: boolean;
    readonly reason: ChatPickReason | null;
    /**
     * Free on an unbilled primary; on a billed one the default's own price, which the server sends beside its
     * name because no listed row carries it, else "price unknown" — never blank (spec 026).
     */
    readonly price: RowPrice;
}

/** A pick as an option's value. The connection name holds no colon, so the first one splits it. */
function optionValue(connection: ChatConnectionName, model: string): string {
    return `${connection}:${model}`;
}

let nextPickerId = 0;

/**
 * The model the next question is asked on (ISC-511, ISC-522): a quiet button beside send that names the
 * pick and its connection, opening a popover with one group per connection — local or billed, its kind,
 * its calls today and its spend — each model with its price, "free" or "price unknown", the default's
 * badge, a check on the current pick, and the chat's total spend at the foot.
 *
 * <p>A spent or unreachable connection's group carries its reason once, on the group, and its rows can
 * be seen but not picked: the operator may want to know what that connection would have cost.
 *
 * <p>A listbox by the keys (ISC-523): opening moves focus onto the current pick, the arrows, Home and End
 * move it between enabled rows only, Enter, Space or a click picks and closes; Escape and a click outside
 * close without picking. Focus goes back to the trigger each time. Placed by `popover-placement.ts`
 * (ISC-524) and re-placed while open.
 */
@Component({
    selector: 'lg-chat-model-picker',
    imports: [Icon, NgTemplateOutlet, TranslocoPipe],
    templateUrl: './chat-model-picker.html',
    styleUrl: './chat-model-picker.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatModelPicker implements OnDestroy {
    private readonly store = inject(ChatStore);
    private readonly dispatch = injectDispatch(chatEvents);
    private readonly transloco = inject(TranslocoService);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly lang = toSignal(this.transloco.langChanges$, {initialValue: this.transloco.getActiveLang()});

    private readonly trigger = viewChild<ElementRef<HTMLButtonElement>>('trigger');
    private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');

    protected readonly id = `lg-chat-model-${nextPickerId++}`;
    protected readonly panelId = `${this.id}-panel`;
    /** Mirrored from the panel's own `toggle` event, so it says what the browser shows. */
    protected readonly expanded = signal(false);
    /** Stops the re-placing while open; null while closed. */
    private stopTracking: (() => void) | null = null;

    constructor() {
        // The `@if` takes trigger and panel away without a close when the choice goes (a catalog of one
        // model): the listeners and the tracking an open panel holds are let go of here, not left behind.
        effect(() => {
            if (!this.showModels()) untracked(() => this.hide());
        });
    }

    /**
     * One group per connection the catalog lists, with what its head shows and why it is disabled; then one
     * head per connection only the spend still names (removed this month, its spend still in the total).
     */
    protected readonly modelGroups = computed<readonly ModelGroup[]>(() => {
        const status = this.store.status();
        const spend = this.store.spend();
        const lang = this.lang();
        const listed = this.store.models()?.connections ?? [];
        const retired = (spend?.connections ?? [])
            .filter((spent) => !listed.some((connection) => connection.name === spent.name))
            .map(
                (spent): ModelGroup => ({
                    name: spent.name,
                    labelKey: CONNECTION_KEYS[spent.name],
                    reason: null,
                    billed: false,
                    kind: '',
                    calls: null,
                    spend: spendOf(spent, lang),
                    options: [],
                    retired: true,
                }),
            );
        const groups = listed.map((connection): ModelGroup => {
            const used = connection.name === 'PRIMARY' ? status?.callsUsed : status?.secondCalls;
            const limit = connection.name === 'PRIMARY' ? status?.callsLimit : status?.secondLimit;
            const spent = spend?.connections.find((c) => c.name === connection.name);
            return {
                name: connection.name,
                labelKey: CONNECTION_KEYS[connection.name],
                reason: unpickableReason(connection, status),
                billed: connection.billed,
                kind: connection.kind,
                calls: used != null && limit != null ? {used, limit} : null,
                spend: spent === undefined ? null : spendOf(spent, lang),
                retired: false,
                options: connection.models.map((model) => ({
                    value: optionValue(connection.name, model.name),
                    model: model.name,
                    billed: connection.billed,
                    isDefault: model.defaultModel,
                    price: priceOf(model, connection.billed, lang),
                })),
            };
        });
        return [...groups, ...retired];
    });
    /**
     * The default as an option of its own, first and outside the groups, when no listed model is flagged
     * as it: the configured model by name, or just "Default" when the server names none. Null when a
     * listed model is the default, which then stands for it.
     *
     * <p>Disabled while the primary connection it runs on has a reason not to be asked; the primary
     * connection's group carries that reason. It stays the current pick while it is the effective one:
     * the store never moves a question onto another connection by itself, which may be billed.
     */
    protected readonly defaultOption = computed<DefaultOption | null>(() => {
        const models = this.store.models();
        if (models === null || this.store.defaultPick() !== null) return null;
        const name = models.defaultModelName?.trim() ?? '';
        const primary = models.connections.find((c) => c.name === 'PRIMARY');
        const billed = primary?.billed ?? false;
        const reason = primary === undefined ? null : unpickableReason(primary, this.store.status());
        const price = defaultPriceOf(billed, models.defaultModelPrice, this.lang());
        const option: DefaultOption =
            name === ''
                ? {key: 'chat.model.defaultBare', params: {}, billed, reason, price}
                : {key: 'chat.model.default', params: {name}, billed, reason, price};
        return option;
    });
    protected readonly defaultValue = DEFAULT_OPTION;
    /** One model in all is no choice: the picker appears only when there is something to pick. */
    readonly showModels = computed(() => hasModelChoice(this.store.models()));
    protected readonly pickValue = computed(() => {
        const pick = this.store.pick() ?? this.store.defaultPick();
        return pick === null ? DEFAULT_OPTION : optionValue(pick.connection, pick.model);
    });

    /**
     * What the trigger names: the picked model (null for the unnamed default), whether its connection is
     * billed, and whether that connection cannot be asked now.
     */
    protected readonly current = computed(() => {
        const pick = this.store.pick() ?? this.store.defaultPick();
        const connection: ChatConnectionName = pick?.connection ?? 'PRIMARY';
        const group = this.modelGroups().find((g) => g.name === connection);
        const named = pick?.model ?? (this.store.models()?.defaultModelName?.trim() || null);
        return {name: named, billed: group?.billed ?? false, unavailable: (group?.reason ?? null) !== null};
    });

    /**
     * The chat's spend in all, written as USD, and the turns its sum leaves out by cause: a model without a
     * price, and usage the provider did not (fully) report.
     */
    protected readonly total = computed(() => {
        const spend = this.store.spend();
        if (spend === null) return null;
        const lang = this.lang();
        const sum = (count: (c: ChatConnectionSpend) => number): number => spend.connections.reduce((total, c) => total + count(c), 0);
        const unpriced = uncountedOf(
            sum((c) => c.unpricedToday),
            sum((c) => c.unpricedMonth),
        );
        const unreported = uncountedOf(
            sum((c) => c.unreportedToday ?? 0),
            sum((c) => c.unreportedMonth ?? 0),
        );
        return {today: formatUsd(spend.today, lang), month: formatUsd(spend.month, lang), unpriced, unreported};
    });

    protected spentReason(reason: ChatPickReason): boolean {
        return reason.key === SPENT_KEY;
    }

    protected toggle(): void {
        if (this.expanded()) this.hide();
        else this.show();
    }

    /** The panel's own `toggle` event: what the browser shows is what `aria-expanded` says. */
    protected onToggle(event: Event): void {
        this.expanded.set((event as ToggleEvent).newState === 'open');
    }

    /** A row picked (ISC-508, ISC-511): the pick for this conversation's next question and regenerate. */
    protected choose(value: string, disabled: boolean): void {
        if (disabled) return;
        this.chooseModel(value);
        this.hide();
        this.trigger()?.nativeElement.focus();
    }

    private chooseModel(value: string): void {
        if (value === DEFAULT_OPTION) {
            this.dispatch.modelPicked(null);
            return;
        }
        const split = value.indexOf(':');
        const connection = value.slice(0, split);
        if (split < 0 || !(connection in CONNECTION_KEYS)) return;
        this.dispatch.modelPicked({connection: connection as ChatConnectionName, model: value.slice(split + 1)});
    }

    /**
     * The keys of the open list (ISC-523): the arrows, Home and End move focus between enabled rows and
     * stop at the ends; Enter and Space pick the focused row. A disabled row is never focused, so no key
     * reaches one.
     */
    protected onKey(event: KeyboardEvent): void {
        const rows = this.enabledRows();
        if (rows.length === 0) return;
        const at = rows.indexOf(this.host.nativeElement.ownerDocument.activeElement as HTMLElement);
        let next: number;
        switch (event.key) {
            case 'ArrowDown':
                next = at < 0 ? 0 : Math.min(at + 1, rows.length - 1);
                break;
            case 'ArrowUp':
                next = at < 0 ? rows.length - 1 : Math.max(at - 1, 0);
                break;
            case 'Home':
                next = 0;
                break;
            case 'End':
                next = rows.length - 1;
                break;
            case 'Enter':
            case ' ':
                if (at < 0) return;
                event.preventDefault();
                this.choose(rows[at].dataset['value'] ?? '', false);
                return;
            default:
                return;
        }
        event.preventDefault();
        rows[next].focus();
    }

    /** Focus moved to a control outside the picker, by Tab or by script: the panel closes, nothing picked. */
    protected onFocusOut(event: FocusEvent): void {
        const next = event.relatedTarget as Node | null;
        if (next !== null && !this.host.nativeElement.contains(next)) this.hide();
    }

    private enabledRows(): HTMLElement[] {
        return Array.from(this.panel()?.nativeElement.querySelectorAll<HTMLElement>(ENABLED_ROW) ?? []);
    }

    private show(): void {
        const panel = this.panel()?.nativeElement;
        const trigger = this.trigger()?.nativeElement;
        if (panel === undefined || trigger === undefined) return;
        panel.showPopover?.();
        // Shown first, so the helper reads the width the stylesheet gave it; placed before the first paint.
        this.stopTracking = track(trigger, panel);
        this.expanded.set(true);
        document.addEventListener('keydown', this.escape, true);
        document.addEventListener('pointerdown', this.outside, true);
        const rows = this.enabledRows();
        (rows.find((row) => row.getAttribute('aria-selected') === 'true') ?? rows[0])?.focus();
    }

    hide(): void {
        document.removeEventListener('keydown', this.escape, true);
        document.removeEventListener('pointerdown', this.outside, true);
        this.stopTracking?.();
        this.stopTracking = null;
        const panel = this.panel()?.nativeElement;
        if (panel?.matches?.(':popover-open')) panel.hidePopover?.();
        this.expanded.set(false);
    }

    private focusInside(): boolean {
        return this.host.nativeElement.contains(this.host.nativeElement.ownerDocument.activeElement);
    }

    /**
     * Escape closes the open panel, in the capture phase while it is open, as the status popover does:
     * with focus inside the picker the key stops here, so neither the docked drawer nor the sheet's
     * `<dialog>` closes on the same press. Focus goes back to the trigger.
     */
    private readonly escape = (event: KeyboardEvent): void => {
        if (event.key !== 'Escape' || !this.expanded()) return;
        const focusInside = this.focusInside();
        if (focusInside) {
            event.preventDefault();
            event.stopPropagation();
        }
        this.hide();
        if (focusInside) this.trigger()?.nativeElement.focus();
    };

    /**
     * A tap or click outside the trigger and the panel closes it without picking. Focus goes back to the
     * trigger once the click is done — unless the click gave it to a control of its own, which keeps it.
     */
    private readonly outside = (event: PointerEvent): void => {
        const target = event.target as Node | null;
        const trigger = this.trigger()?.nativeElement;
        const panel = this.panel()?.nativeElement;
        if (target && (trigger?.contains(target) || panel?.contains(target))) return;
        const focusInside = this.focusInside();
        this.hide();
        if (!focusInside) return;
        setTimeout(() => {
            const active = this.host.nativeElement.ownerDocument.activeElement;
            if (active === null || active === this.host.nativeElement.ownerDocument.body) trigger?.focus();
        });
    };

    ngOnDestroy(): void {
        this.hide();
    }
}

/**
 * The separate default row's price: free on an unbilled primary, the server's `defaultModelPrice` on a billed
 * one, else unknown — absent from an older server, or a default nobody priced.
 */
function defaultPriceOf(billed: boolean, price: ChatModelPrice | null | undefined, lang: string): RowPrice {
    if (!billed) return {kind: 'free'};
    if (price == null) return {kind: 'unknown'};
    return {kind: 'priced', input: formatUsdPrice(price.input, lang), output: formatUsdPrice(price.output, lang)};
}

/**
 * A model's price as its row shows it: free on an unbilled connection, unknown on an unpriced billed one, else
 * the configured pair with every digit it was configured with — a price, not a spent amount, is never rounded.
 */
function priceOf(model: ChatModel, billed: boolean, lang: string): RowPrice {
    if (!billed || model.free === true) return {kind: 'free'};
    if (model.price == null) return {kind: 'unknown'};
    return {kind: 'priced', input: formatUsdPrice(model.price.input, lang), output: formatUsdPrice(model.price.output, lang)};
}
