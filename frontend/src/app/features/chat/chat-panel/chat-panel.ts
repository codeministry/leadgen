import {
    afterNextRender,
    afterRenderEffect,
    ChangeDetectionStrategy,
    Component,
    computed,
    DestroyRef,
    effect,
    ElementRef,
    inject,
    Injector,
    signal,
    untracked,
    viewChild,
} from '@angular/core';
import {NgTemplateOutlet} from '@angular/common';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {NavigationEnd, Router} from '@angular/router';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {injectDispatch} from '@ngrx/signals/events';
import {filter} from 'rxjs';
import {ChatApi} from '@core/api/chat.api';
import {ChatSuggestion, conversationTitle, formatChatCtx, SuggestionsFor, TITLE_MAX, TurnView} from '@core/model/chat';
import {chatEvents} from '@core/store/chat.events';
import {ChatStore} from '@core/store/chat.store';
import {Icon} from '@shared/icon/icon';
import {LgIconName} from '@shared/icon/lucide-icons';
import {LivingMarkFrame} from '@shared/living-mark/living-mark';
import {SideDrawer} from '@shared/side-drawer/side-drawer';
import {ChatAnswer} from '../chat-answer/chat-answer';
import {ChatComposer} from '../chat-composer/chat-composer';
import {ChatDeleteDialog} from '../chat-history/chat-delete-dialog';
import {ChatHistory} from '../chat-history/chat-history';
import {ChatMinibar} from '../chat-minibar/chat-minibar';
import {turnFrame} from './turn-frame';
import {ChatStatusPopover} from '../chat-status-popover/chat-status-popover';
import {TurnLike, TurnStatus, cachedTurnStatus} from '../chat-status-popover/turn-status';

const RAIL_QUERY = '(width >= 80rem)';
/** Below it the drawer is a full-screen sheet, and following a source folds it into the bar. */
const SHEET_QUERY = '(width < 48rem)';
/** A per-browser convenience, never the URL: whether the 80rem rail is open. */
const RAIL_KEY = 'lg-chat-rail';
/** How near the bottom still counts as at it: a wheel notch short of the end is still following (ISC-462). */
const FOLLOW_SLACK_PX = 24;

/** At most this many suggestions are shown, whatever the server answers (ISC-456). */
const SUGGESTIONS_MAX = 4;
/** Rows held while the server answers, each of a suggestion's final height. */
const SKELETON_ROWS = [0, 1, 2] as const;

/** What the empty chat asks suggestions for, under a key that names it. */
interface SuggestionTarget {
    readonly key: string;
    readonly for: SuggestionsFor;
}

/** The suggestions of one target: null while they load; once set, never replaced for that target. */
interface SuggestionState {
    readonly key: string;
    readonly items: readonly ChatSuggestion[] | null;
}

/**
 * A trigger's icon (design § 7), in muted ink. Matched by the words the server's trigger names carry,
 * so a trigger added later still gets a fitting glyph or the neutral question mark, never an error.
 */
function triggerIcon(trigger: string): LgIconName {
    const name = trigger.toUpperCase();
    if (name.includes('DEADLINE') || name.includes('CLOSE')) return 'calendar-clock';
    if (name.includes('ANSWER') || name.includes('REPLY')) return 'mail-question';
    if (name.includes('TAG') || name.includes('TREND') || name.includes('RISING')) return 'trending-up';
    if (name.includes('PIN') || name.includes('OFFER') || name.includes('CONTEXT')) return 'pin';
    if (name.includes('NEW') || name.includes('INTAKE')) return 'inbox';
    return 'message-circle-question';
}

function readRail(): boolean {
    try {
        return localStorage.getItem(RAIL_KEY) !== 'closed';
    } catch {
        return true;
    }
}

/**
 * The chat drawer's panel (ISC-432): its head, the conversation list, the thread and the composer.
 *
 * <p>**`?chat` opens it, nothing else.** Whether it is open is `ChatStore.view()`, which the URL
 * decides; the buttons here only dispatch the intents that write the URL, and this component
 * follows. So a reload, a change of screen and a followed source all land where the URL says —
 * following a source keeps `?chat`, so the panel simply stays. Below 48rem it stays folded:
 * the sheet would cover the page the source opened, so it collapses into `chat-minibar` above
 * the bottom navigation (ISC-441). Folded is UI state held here, never a value of `?chat`.
 *
 * <p>**The frame is `shared/side-drawer`**, docked beside the page from 48rem and a full-screen
 * modal sheet below it. The width is the stepped `--lg-chat-w`, or from 80rem with the rail folded
 * `--lg-chat-thread-w`, handed to the drawer through its `width` input.
 *
 * <p>**The thread shows each turn's question and hands its answer to `chat-answer`**, which renders
 * the Markdown with its citations as pills and marks, the steps, the sources, the actions and, for a
 * turn that ended short, the reason; the `data-slot` marks are where each goes.
 */
@Component({
    selector: 'lg-chat-panel',
    imports: [ChatAnswer, ChatComposer, ChatDeleteDialog, ChatHistory, ChatMinibar, ChatStatusPopover, Icon, NgTemplateOutlet, SideDrawer, TranslocoPipe],
    templateUrl: './chat-panel.html',
    styleUrl: './chat-panel.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatPanel {
    protected readonly store = inject(ChatStore);
    private readonly dispatch = injectDispatch(chatEvents);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly transloco = inject(TranslocoService);
    private readonly drawer = viewChild.required(SideDrawer);
    private readonly deleteDialog = viewChild.required(ChatDeleteDialog);
    private readonly injector = inject(Injector);
    private readonly api = inject(ChatApi);

    protected readonly titleMax = TITLE_MAX;
    /** The open conversation, while the head shows it: what its "⋯" renames and deletes. */
    protected readonly headConversation = computed(() => (this.listView() || this.store.view() !== 'conversation' ? null : this.store.conversation()));
    /** Whether the head's title is an input right now (ISC-449). */
    protected readonly renamingHead = signal(false);
    /** What an emptied title falls back to, shown as the placeholder so clearing the field shows it. */
    protected readonly derivedTitle = computed(() => {
        const first = this.headConversation()?.turns[0]?.question ?? '';
        return first.trim() === '' ? this.transloco.translate('chat.rename.placeholder') : conversationTitle(first);
    });

    private readonly railWide = signal(typeof matchMedia === 'function' && matchMedia(RAIL_QUERY).matches);
    private readonly railPreferred = signal(readRail());
    /** From 80rem the conversations dock inside the panel; below, they are a sub-view in its body. */
    protected readonly rail = computed(() => this.railWide() && this.railPreferred());
    /**
     * The drawer's width (ISC-445): the stepped `--lg-chat-w`, which from 80rem is the thread plus the
     * rail; with the rail folded there, the thread alone, so the fold narrows the panel by exactly the
     * rail and the page takes it, while the thread's box stays the same.
     */
    protected readonly width = computed(() => (this.railWide() && !this.railPreferred() ? 'var(--lg-chat-thread-w)' : 'var(--lg-chat-w)'));
    protected readonly listView = computed(() => this.store.view() === 'list' && !this.railWide());
    private readonly sheet = signal(typeof matchMedia === 'function' && matchMedia(SHEET_QUERY).matches);
    /**
     * The sheet folded into the bar (ISC-441). UI state and never the URL: `?chat` says the
     * conversation is open, and folded or not is how it is shown on this screen right now.
     */
    protected readonly collapsed = signal(false);
    protected readonly open = computed(() => this.store.present() === true && this.store.view() !== 'closed' && !this.collapsed());
    /** Where the thread stood when the sheet folded, so expanding lands on the same turn. */
    private threadTop = 0;
    protected readonly empty = computed(() => this.store.turns().length === 0 && this.store.liveTurn() === null);
    /**
     * What the empty chat asks suggestions for (ISC-456): the open conversation, or the pins a new one is
     * asked under. Equal by key, so a reload of the same conversation is no new target.
     */
    private readonly suggestionTarget = computed<SuggestionTarget | null>(
        () => {
            if (!this.empty() || this.listView()) return null;
            const view = this.store.view();
            if (view === 'new') {
                const context = this.store.context();
                return {key: `new:${formatChatCtx(context) ?? ''}`, for: {context}};
            }
            const conversation = this.store.conversation();
            if (view !== 'conversation' || conversation === null || this.store.loading()) return null;
            return {key: `conversation:${conversation.id}`, for: {conversationId: conversation.id}};
        },
        {equal: (a, b) => a?.key === b?.key},
    );
    private readonly suggestionState = signal<SuggestionState | null>(null);
    /** Null while they load, which draws the skeleton rows. */
    protected readonly suggestions = computed<readonly ChatSuggestion[] | null>(() => {
        const target = this.suggestionTarget();
        const state = this.suggestionState();
        return target === null || state?.key !== target.key ? null : state.items;
    });
    protected readonly skeletonRows = SKELETON_ROWS;
    protected readonly triggerIcon = triggerIcon;
    protected readonly titleKey = computed(() => (this.listView() ? 'chat.history' : 'chat.new'));
    /**
     * Whether the thread follows the stream (ISC-462): only while the reader is at its bottom. Set by
     * the reader's own scrolling, never by the stream, so text arriving can not pull a reader back down.
     */
    private readonly following = signal(true);
    /** Scrolled up while a turn is written: the way back down, and to following again. */
    protected readonly showJump = computed(() => this.store.streaming() && !this.following());

    constructor() {
        const destroyRef = inject(DestroyRef);
        if (typeof matchMedia === 'function') {
            const rail = matchMedia(RAIL_QUERY);
            const onRail = (event: MediaQueryListEvent) => this.railWide.set(event.matches);
            rail.addEventListener('change', onRail);
            const sheet = matchMedia(SHEET_QUERY);
            const onSheet = (event: MediaQueryListEvent) => this.sheet.set(event.matches);
            sheet.addEventListener('change', onSheet);
            destroyRef.onDestroy(() => {
                rail.removeEventListener('change', onRail);
                sheet.removeEventListener('change', onSheet);
            });
        }

        // Following a source: a change of screen that keeps `?chat` as it was, while the sheet
        // covers the window. The page it opened is what the reader went for, so the sheet folds.
        const router = inject(Router);
        let previous: {path: string; chat: string | null} | null = null;
        router.events
            .pipe(
                filter((event) => event instanceof NavigationEnd),
                takeUntilDestroyed(destroyRef),
            )
            .subscribe((event) => {
                const path = event.urlAfterRedirects.split(/[?#]/)[0];
                const chat = router.parseUrl(event.urlAfterRedirects).queryParamMap.get('chat');
                // A link that did not carry `?chat` while the chat is open: the store puts it back with
                // the next navigation (ISC-444), which is the one compared against the screen before.
                if (chat === null && this.store.view() !== 'closed') return;
                const followed = previous !== null && previous.path !== path && chat !== null && previous.chat === chat;
                previous = {path, chat};
                if (followed && this.sheet() && this.open()) this.collapsed.set(true);
            });

        // The bar lives only while the conversation is open on a sheet: closed, or docked from
        // 48rem, there is nothing left to fold.
        effect(() => {
            if (this.store.view() === 'closed' || !this.sheet()) untracked(() => this.collapsed.set(false));
        });

        // After render, so the drawer exists the first time `?chat` is already in the URL.
        afterRenderEffect(() => {
            const open = this.open();
            untracked(() => {
                const drawer = this.drawer();
                if (open && !drawer.isOpen()) drawer.open(this.trigger(), () => this.reveal());
                if (!open && drawer.isOpen()) {
                    this.threadTop = this.thread()?.scrollTop ?? 0;
                    drawer.close();
                }
            });
        });

        // Every chunk, after it is laid out: kept in view while following, left alone otherwise. A new
        // turn starts following again — the reader just asked, so the answer is what they wait for.
        let streamed = false;
        afterRenderEffect(() => {
            const streaming = this.store.streaming();
            this.store.liveTurn();
            untracked(() => {
                if (streaming && !streamed) this.following.set(true);
                streamed = streaming;
                if (streaming && this.following()) this.toBottom();
            });
        });
        // The answer renders throttled (chat-answer, fix 3F-9): its trailing render grows the thread with
        // no new chunk and no signal here, so growth is watched in the DOM itself. Text and nodes only —
        // the composer resizing its own field is an attribute change and moves nothing here.
        if (typeof MutationObserver === 'function') {
            const grown = new MutationObserver(() => {
                if (this.store.streaming() && this.following()) this.toBottom();
            });
            grown.observe(this.host.nativeElement, {childList: true, characterData: true, subtree: true});
            destroyRef.onDestroy(() => grown.disconnect());
        }

        // The empty chat's suggestions: asked once per target. A sentence on screen is never swapped out
        // under the finger — a set target keeps its sentences, and a late answer for another target is dropped.
        effect((onCleanup) => {
            const target = this.suggestionTarget();
            if (target === null) return;
            untracked(() => {
                if (this.suggestionState()?.key === target.key) return;
                this.suggestionState.set({key: target.key, items: null});
                const settle = (items: readonly ChatSuggestion[]) =>
                    this.suggestionState.update((state) =>
                        state?.key === target.key && state.items === null ? {key: target.key, items: items.slice(0, SUGGESTIONS_MAX)} : state,
                    );
                const subscription = this.api.suggestions(target.for).subscribe({
                    next: settle,
                    // The server already falls back to its catalog sentences; a failed request shows none.
                    error: () => settle([]),
                });
                onCleanup(() => {
                    subscription.unsubscribe();
                    // Left before the answer came: the next visit asks again rather than holding skeletons.
                    this.suggestionState.update((state) => (state?.key === target.key && state.items === null ? null : state));
                });
            });
        });

        // The rail lists the conversations beside an open one, without moving `?chat`.
        effect(() => {
            if (this.open() && this.rail()) untracked(() => this.dispatch.conversationsRequested());
        });
    }

    /** The living mark beside an answer (ISC-465): a stored turn and the live one read the same way. */
    protected frameOf(turn: Pick<TurnView, 'state' | 'steps'>): LivingMarkFrame {
        return turnFrame(turn.state, turn.steps);
    }

    /** What the ring beside an answer says on hover or focus (ISC-474), for a stored and a live turn alike. */
    protected statusOf(turn: TurnLike): TurnStatus {
        return cachedTurnStatus(turn);
    }

    protected close(): void {
        this.dispatch.closeRequested();
    }

    /** The drawer closed on its own (a dialog closed by the platform): the URL follows — unless it folded into the bar. */
    protected onClosed(): void {
        if (this.store.view() !== 'closed' && !this.collapsed()) this.dispatch.closeRequested();
    }

    /** The bar's tap: the sheet again, over the page it opened, and `?chat` as it was. */
    protected expand(): void {
        this.collapsed.set(false);
    }

    protected startNew(): void {
        this.dispatch.newRequested({pinnedOfferId: null});
    }

    protected openConversation(id: number): void {
        this.dispatch.openRequested(id);
    }

    /** From 80rem it folds the rail; below, it swaps the body between the thread and the list. */
    protected toggleConversations(): void {
        if (this.railWide()) {
            const next = !this.railPreferred();
            this.railPreferred.set(next);
            try {
                localStorage.setItem(RAIL_KEY, next ? 'open' : 'closed');
            } catch {
                // Storage refused (a private window): the rail still toggles for this visit.
            }
            return;
        }
        if (this.store.view() !== 'list') {
            this.dispatch.listRequested();
            return;
        }
        const last = this.store.lastId();
        if (last !== null) this.dispatch.openRequested(last);
        else this.startNew();
    }

    /** The head's popover sits in the top layer: placed under its "⋯" just before it opens. */
    protected placeHeadMenu(event: MouseEvent): void {
        const menu = document.getElementById('lg-chat-head-menu');
        if (menu === null) return;
        const box = (event.currentTarget as HTMLElement).getBoundingClientRect();
        menu.style.top = `${box.bottom + 4}px`;
        menu.style.right = `${Math.max(8, window.innerWidth - box.right)}px`;
    }

    protected startHeadRename(): void {
        this.hideHeadMenu();
        this.renamingHead.set(true);
        afterNextRender(
            () => {
                const input = this.host.nativeElement.querySelector<HTMLInputElement>('.lg-chat-title-input');
                input?.focus();
                input?.select();
            },
            {injector: this.injector},
        );
    }

    /** Enter saves, Escape restores the old title; focus returns to the head's "⋯" either way. */
    protected headRenameKey(event: KeyboardEvent): void {
        if (event.key === 'Enter') {
            event.preventDefault();
            this.commitHeadRename(event);
        } else if (event.key === 'Escape') {
            // Escape here restores the title; it must not also close the sheet it sits in.
            event.preventDefault();
            event.stopPropagation();
            this.renamingHead.set(false);
            this.focusHeadMore();
        }
    }

    protected commitHeadRename(event: Event): void {
        const conversation = this.headConversation();
        if (!this.renamingHead() || conversation === null) return;
        this.renamingHead.set(false);
        const title = (event.target as HTMLInputElement).value.trim();
        if (title !== conversation.title) this.dispatch.renameRequested({id: conversation.id, title});
        if (event.type !== 'blur') this.focusHeadMore();
    }

    /** Deleting the open conversation from the head: after it, `?chat=new`, and focus on the composer's field. */
    protected askDeleteOpen(id: number, title: string): void {
        this.hideHeadMenu();
        this.deleteDialog().ask({
            id,
            title,
            back: document.getElementById('lg-chat-head-more'),
            next: this.host.nativeElement.querySelector<HTMLElement>('#lg-chat-title'),
        });
    }

    private hideHeadMenu(): void {
        const menu = document.getElementById('lg-chat-head-menu');
        if (menu?.matches?.(':popover-open')) menu.hidePopover?.();
    }

    private focusHeadMore(): void {
        afterNextRender(() => document.getElementById('lg-chat-head-more')?.focus(), {injector: this.injector});
    }

    /** A suggestion is asked as it reads: the server wrote it in the reader's language. */
    protected ask(text: string): void {
        this.dispatch.asked(text);
    }

    /** The reader's scroll decides following: at the bottom, within a small slack, or not. */
    protected onThreadScroll(): void {
        const thread = this.thread();
        if (thread === null) return;
        this.following.set(thread.scrollHeight - thread.scrollTop - thread.clientHeight <= FOLLOW_SLACK_PX);
    }

    /** "Jump to latest": to the end, following again, and focus kept in the thread the button sat over. */
    protected jumpToLatest(): void {
        this.toBottom();
        this.following.set(true);
        this.thread()?.focus({preventScroll: true});
    }

    /** Instant, never smooth: a smooth scroll's own intermediate events would read as the reader leaving the bottom. */
    private toBottom(): void {
        const thread = this.thread();
        if (thread !== null) thread.scrollTop = thread.scrollHeight;
    }

    /** Focus comes back to the header's button on every close (DS-APP-35), wherever it was opened from. */
    private trigger(): HTMLElement | null {
        return document.querySelector<HTMLElement>('.lg-chat-open') ?? (document.activeElement as HTMLElement | null);
    }

    /** Once the frame is rendered: the thread back where it stood if it folded, then the focus target. */
    private reveal(): HTMLElement | null {
        const thread = this.thread();
        if (thread !== null && this.threadTop > 0) thread.scrollTop = this.threadTop;
        this.threadTop = 0;
        return this.focusTarget();
    }

    private thread(): HTMLElement | null {
        return this.host.nativeElement.querySelector<HTMLElement>('.lg-chat-thread');
    }

    /** Typing is the task on a fine pointer; on a coarse one the keyboard would cover the thread. */
    private focusTarget(): HTMLElement | null {
        const coarse = typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches;
        const root = this.host.nativeElement;
        const input = root.querySelector<HTMLElement>('.lg-chat-input');
        return coarse || input === null ? root.querySelector<HTMLElement>('#lg-chat-title') : input;
    }
}
