import {
    afterRenderEffect,
    ChangeDetectionStrategy,
    Component,
    computed,
    DestroyRef,
    effect,
    ElementRef,
    inject,
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
import {chatEvents} from '@core/store/chat.events';
import {ChatStore} from '@core/store/chat.store';
import {Icon} from '@shared/icon/icon';
import {LgIconName} from '@shared/icon/lucide-icons';
import {SideDrawer} from '@shared/side-drawer/side-drawer';
import {ChatAnswer} from '../chat-answer/chat-answer';
import {ChatComposer} from '../chat-composer/chat-composer';
import {ChatMinibar} from '../chat-minibar/chat-minibar';

const RAIL_QUERY = '(width >= 80rem)';
/** Below it the drawer is a full-screen sheet, and following a source folds it into the bar. */
const SHEET_QUERY = '(width < 48rem)';
/** A per-browser convenience, never the URL: whether the 80rem rail is open. */
const RAIL_KEY = 'lg-chat-rail';

interface Suggestion {
    readonly key: string;
    readonly icon: LgIconName;
}

const SUGGESTIONS: readonly Suggestion[] = [
    {key: 'chat.suggest.spring', icon: 'search'},
    {key: 'chat.suggest.kafka', icon: 'chart-column'},
    {key: 'chat.suggest.wrote', icon: 'file-text'},
    {key: 'chat.suggest.references', icon: 'users'},
];

const PINNED_SUGGESTIONS: readonly Suggestion[] = [
    {key: 'chat.suggest.pinned.fit', icon: 'scale'},
    {key: 'chat.suggest.pinned.similar', icon: 'scan-search'},
    {key: 'chat.suggest.pinned.wrote', icon: 'file-text'},
    {key: 'chat.suggest.pinned.lack', icon: 'circle-help'},
];

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
 * modal sheet below it. The width is the stepped `--lg-chat-w`, handed to the drawer through
 * `--lg-side-drawer-w` on this host.
 *
 * <p>**The thread shows each turn's question and hands its answer to `chat-answer`**, which renders
 * the Markdown with its citations as pills and marks, the steps, the sources, the actions and, for a
 * turn that ended short, the reason; the `data-slot` marks are where each goes.
 */
@Component({
    selector: 'lg-chat-panel',
    imports: [ChatAnswer, ChatComposer, ChatMinibar, Icon, NgTemplateOutlet, SideDrawer, TranslocoPipe],
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

    private readonly railWide = signal(typeof matchMedia === 'function' && matchMedia(RAIL_QUERY).matches);
    private readonly railPreferred = signal(readRail());
    /** From 80rem the conversations dock inside the panel; below, they are a sub-view in its body. */
    protected readonly rail = computed(() => this.railWide() && this.railPreferred());
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
    protected readonly suggestions = computed(() =>
        this.store.view() === 'new' && this.store.pinnedOfferId() !== null ? PINNED_SUGGESTIONS : SUGGESTIONS,
    );
    protected readonly titleKey = computed(() => (this.listView() ? 'chat.history' : 'chat.new'));

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

        // The rail lists the conversations beside an open one, without moving `?chat`.
        effect(() => {
            if (this.open() && this.rail()) untracked(() => this.dispatch.conversationsRequested());
        });
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

    /** A suggestion is sent as it reads, in the reader's language. */
    protected ask(key: string): void {
        this.dispatch.asked(this.transloco.translate(key));
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
