import {ChangeDetectionStrategy, Component, computed, DestroyRef, effect, inject, Injector, input, signal, untracked, viewChild} from '@angular/core';
import {DomSanitizer, SafeHtml} from '@angular/platform-browser';
import {Router} from '@angular/router';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {injectDispatch} from '@ngrx/signals/events';
import {ChatApi} from '@core/api/chat.api';
import {ChatFollowUp, ChatSource, ChatStatisticsSource, ChatStep, ChatTurnSource, ChatTurnState, isStatistics} from '@core/model/chat';
import {chatEvents} from '@core/store/chat.events';
import {Icon} from '@shared/icon/icon';
import {ChatSteps} from '../chat-steps/chat-steps';
import {CitationCard} from '../citation-card/citation-card';
import {ANSWER_RENDERER, Cite, citeCommands, distinctSources, parseCite} from './answer-markdown';
import {ChatSources} from './sources/chat-sources';
import {ChatStatisticsCard} from './statistics-card/statistics-card';

/** How long the copy button shows its tick, the reveal duration's order of magnitude. */
const COPIED_MS = 1200;
/** At most this many follow-ups are shown, whatever the server answers (ISC-457). */
const FOLLOWUPS_MAX = 3;
/** The live turn's render interval: twenty renders a second at most, however finely the server chunks. */
const LIVE_RENDER_MS = 50;

/**
 * One assistant answer: its tool steps, its Markdown with the citations as pills and marks, the
 * sources under it and, once it has finished, copy and regenerate (ISC-429, -430, -435, -440, -442).
 *
 * <p>**Why the HTML is trusted past Angular's sanitizer.** DOMPurify is the sanitizer here — the
 * spec names it, and it runs over every byte `marked` produced, raw HTML already escaped to text.
 * Every `href`, `data-cite` and `data-unverified` in the result is set afterwards from the
 * renderer's own slot map, never read back from parsed markup (fix 3F-1); Angular's sanitizer would
 * strip the data attributes the preview reads, so that output is handed over as it is. Nothing
 * else may reach `bypassSecurityTrustHtml`.
 *
 * <p>**A pill is followed through the router**, not by the browser: its `href` is the real URL
 * (so a middle click and "copy link" work), and a plain click navigates in-app with `?chat`
 * preserved, so the drawer stays open beside the row it opened.
 *
 * <p>**The listeners sit on the host, delegated.** The pills and marks are rendered Markdown, so
 * they carry no bindings of their own; each is itself focusable (an anchor, or a mark with
 * `tabindex="0"`), and hover and focus reach the preview alike through `mouseover`/`focusin`.
 *
 * <p>**The caret is a pseudo-element on the last block while the turn streams** — one of the four
 * `--lg-ai` marks — so no chunk has to be spliced into the Markdown to place it.
 */
@Component({
    selector: 'lg-chat-answer',
    imports: [ChatSources, ChatStatisticsCard, ChatSteps, CitationCard, Icon, TranslocoPipe],
    templateUrl: './chat-answer.html',
    styleUrl: './chat-answer.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: {
        '(click)': 'onClick($event)',
        '(mouseover)': 'onEnter($event)',
        '(focusin)': 'onEnter($event)',
        '(mouseout)': 'onLeave($event)',
        '(focusout)': 'onLeave($event)',
        '(keydown)': 'onKeydown($event)',
    },
})
export class ChatAnswer {
    readonly answer = input('');
    readonly sources = input<readonly ChatTurnSource[]>([]);
    /** The rows the answer cites; the statistics are drawn as cards of their own (ISC-460). */
    protected readonly rows = computed<readonly ChatSource[]>(() => this.sources().filter((s): s is ChatSource => !isStatistics(s)));
    readonly steps = input<readonly ChatStep[]>([]);
    readonly state = input<ChatTurnState>('DONE');
    readonly turnId = input<number | null>(null);
    /** What `failure` interpolates, as text: the server's sentence for a model failure. */
    readonly failureParams = input<Readonly<Record<string, string>> | null>(null);
    /**
     * Why the turn ended short, as a catalog key or the server's sentence: a spent budget and spent
     * rounds are limits with their own words, not a broken answer (ISC-433).
     */
    readonly failure = input<string | null>(null);
    /** The last answer in the thread: only it can be written again, and only it carries follow-ups. */
    readonly last = input(false);
    /** The conversation the turn belongs to; the follow-ups are asked for under it. */
    readonly conversationId = input<number | null>(null);

    /** The turn's `statistics` calls, each drawn as a card from its own result (ISC-460). */
    protected readonly statistics = computed<readonly ChatStatisticsSource[]>(() => this.sources().filter(isStatistics));

    private readonly router = inject(Router);
    private readonly sanitizer = inject(DomSanitizer);
    private readonly transloco = inject(TranslocoService);
    private readonly dispatch = injectDispatch(chatEvents);
    private readonly card = viewChild.required(CitationCard);

    protected readonly streaming = computed(() => this.state() === 'STREAMING');
    protected readonly finished = computed(() => !this.streaming() && this.answer().trim() !== '');
    protected readonly canRegenerate = computed(() => this.finished() && this.last() && this.turnId() !== null);
    protected readonly copied = signal(false);

    /** What the server answered for which turn: a turn id guards against a late answer for another. */
    private readonly loadedFollowUps = signal<{readonly turnId: number; readonly items: readonly ChatFollowUp[]} | null>(null);
    private requestedFollowUps: number | null = null;
    /**
     * Beneath a finished turn only, and only while it is the last (ISC-457): sending the next
     * question makes the live turn the last one, and these go with it.
     */
    protected readonly followUps = computed<readonly ChatFollowUp[]>(() => {
        const loaded = this.loadedFollowUps();
        if (loaded === null || loaded.turnId !== this.turnId() || this.state() !== 'DONE' || !this.last()) return [];
        return loaded.items;
    });

    private readonly render = inject(ANSWER_RENDERER)({
        href: (cite) => this.url(cite, true),
        source: (cite) => this.find(cite),
        unverifiedWord: () => this.transloco.translate('chat.answer.unverified'),
        citeLabel: (n, title) => this.transloco.translate('chat.cite.label', {n, title: title ?? ''}).trim(),
    });

    /** Bumped by the trailing timer of a throttled live render, so the latest text is shown without a new chunk. */
    private readonly renderTick = signal(0);
    private rendered: {readonly text: string; readonly sources: readonly ChatSource[]; readonly html: SafeHtml} | null = null;
    private renderedAt = Number.NEGATIVE_INFINITY;
    private renderTimer: ReturnType<typeof setTimeout> | undefined;

    /**
     * The answer as HTML, parsed as seldom as it can be (fix 3F-9). A stored answer is rendered once
     * for its text and its sources. A streaming one would otherwise be re-parsed and re-sanitised
     * whole for every delta, which is quadratic in its length; it is rendered at most once per
     * `LIVE_RENDER_MS`, leading and trailing, so it still visibly grows chunk by chunk (ISC-435) and
     * the last text is never left unshown. The timer only bumps a signal; the render stays here.
     */
    protected readonly html = computed(() => {
        this.renderTick();
        const text = this.answer();
        // Read so a late `sources` event relabels the pills.
        const sources = this.rows();
        const last = this.rendered;
        if (last !== null && last.text === text && last.sources === sources) return last.html;
        if (this.streaming() && last !== null) {
            const wait = this.renderedAt + LIVE_RENDER_MS - performance.now();
            if (wait > 0) {
                this.renderTimer ??= setTimeout(() => {
                    this.renderTimer = undefined;
                    this.renderTick.update((n) => n + 1);
                }, wait);
                return last.html;
            }
        }
        const html = this.sanitizer.bypassSecurityTrustHtml(this.render(text));
        this.rendered = {text, sources, html};
        this.renderedAt = performance.now();
        return html;
    });

    private copiedTimer: ReturnType<typeof setTimeout> | undefined;

    constructor() {
        const destroyRef = inject(DestroyRef);
        destroyRef.onDestroy(() => {
            clearTimeout(this.copiedTimer);
            clearTimeout(this.renderTimer);
        });

        // Resolved only when a finished last turn asks, so an answer rendered without a server never needs one.
        const injector = inject(Injector);
        effect((onCleanup) => {
            const conversationId = this.conversationId();
            const turnId = this.turnId();
            if (conversationId === null || turnId === null || this.state() !== 'DONE' || !this.last()) return;
            untracked(() => {
                if (this.requestedFollowUps === turnId) return;
                this.requestedFollowUps = turnId;
                const subscription = injector
                    .get(ChatApi)
                    .followups(conversationId, turnId)
                    .subscribe({
                        next: (items) => this.loadedFollowUps.set({turnId, items: items.slice(0, FOLLOWUPS_MAX)}),
                        // No follow-ups is a complete answer; a failed fetch shows none rather than an error under the answer.
                        error: () => this.loadedFollowUps.set({turnId, items: []}),
                    });
                onCleanup(() => subscription.unsubscribe());
            });
        });
    }

    /** A follow-up is asked as it reads. */
    protected askFollowUp(text: string): void {
        this.dispatch.asked(text);
    }

    /** A plain click on a pill navigates in-app; a modified one is left to the browser. */
    protected onClick(event: MouseEvent): void {
        const pill = (event.target as Element | null)?.closest?.('a.lg-chat-cite');
        if (!(pill instanceof HTMLAnchorElement)) return;
        if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        const cite = parseCite(`cite:${(pill.dataset['cite'] ?? '').toLowerCase()}`);
        if (cite === null) return;
        event.preventDefault();
        this.card().close();
        void this.router.navigateByUrl(this.url(cite, true));
    }

    /** Hover or focus on a pill or an unverified mark opens its preview; nothing navigates. */
    protected onEnter(event: Event): void {
        const target = (event.target as Element | null)?.closest?.('a.lg-chat-cite, .lg-chat-unverified');
        if (!(target instanceof HTMLElement)) return;
        const unverified = target.dataset['unverified'];
        if (unverified !== undefined) {
            this.card().open(target, {kind: 'unverified', id: unverified});
            return;
        }
        const cite = parseCite(`cite:${(target.dataset['cite'] ?? '').toLowerCase()}`);
        if (cite === null) return;
        const row = this.find(cite);
        const fallback = this.transloco.translate(cite.kind === 'OFFER' ? 'chat.sources.offer' : 'chat.sources.application');
        this.card().open(target, {
            kind: 'cite',
            title: row?.title ?? `${fallback} ${cite.id}`,
            source: row?.source ?? null,
            date: row?.date ?? null,
        });
    }

    /** Pointer leaving the mark, or focus leaving it: the card goes. */
    protected onLeave(event: Event): void {
        const from = (event.target as Element | null)?.closest?.('a.lg-chat-cite, .lg-chat-unverified');
        const to = (event as MouseEvent | FocusEvent).relatedTarget as Node | null;
        if (from instanceof HTMLElement && (to === null || !from.contains(to))) this.card().close();
    }

    protected onKeydown(event: KeyboardEvent): void {
        if (event.key === 'Escape' && this.card().isOpen()) {
            this.card().close();
            // The drawer's own Escape would close the whole panel; this one only closes the card.
            event.stopPropagation();
        }
    }

    protected async copy(): Promise<void> {
        const sources = distinctSources(this.rows());
        const links = sources.map((s) => `[${s.n}] ${s.title}: ${location.origin}${this.url(s, false)}`);
        const text = links.length === 0 ? this.answer() : `${this.answer()}\n\n${links.join('\n')}`;
        try {
            await navigator.clipboard.writeText(text);
            this.copied.set(true);
            clearTimeout(this.copiedTimer);
            this.copiedTimer = setTimeout(() => this.copied.set(false), COPIED_MS);
        } catch {
            // Clipboard refused (an insecure origin, a denied permission): the button simply does not tick.
        }
    }

    protected regenerate(): void {
        const id = this.turnId();
        if (id !== null) this.dispatch.regenerateRequested(id);
    }

    private find(cite: Cite): ChatSource | undefined {
        return this.rows().find((s) => s.kind === cite.kind && s.id === cite.id);
    }

    /** The row's URL; inside the app it keeps `?chat`, on the clipboard it stands alone. */
    private url(cite: Cite, keepChat: boolean): string {
        const tree = this.router.createUrlTree(citeCommands(cite), keepChat ? {queryParamsHandling: 'preserve'} : {});
        return this.router.serializeUrl(tree);
    }
}

