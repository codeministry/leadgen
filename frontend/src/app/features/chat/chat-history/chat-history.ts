import {afterNextRender, ChangeDetectionStrategy, Component, computed, ElementRef, inject, Injector, output, signal} from '@angular/core';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {injectDispatch} from '@ngrx/signals/events';
import {ConversationSummary, TITLE_MAX} from '@core/model/chat';
import {chatEvents} from '@core/store/chat.events';
import {ChatStore} from '@core/store/chat.store';
import {Icon} from '@shared/icon/icon';
import {DeleteAsk} from './chat-delete-dialog';
import {activityLabel, activityOf, groupByActivity, TimeGroupKey} from './time-groups';

let instances = 0;

/**
 * The conversation list (ISC-447, ISC-449, ISC-450), drawn the same in the 80rem rail and in the
 * list sub-view below it: the search field, the time groups with their sticky headings, and each
 * row with its "⋯" menu (Rename, Delete).
 *
 * <p>**The store holds the list and the query; this component holds only what is being renamed.**
 * The search is sent through the store, debounced there, and the previous result stays drawn with
 * `aria-busy` while the next one loads, so nothing jumps to empty in between.
 *
 * <p>**Delete is asked, not done, here**: the panel owns the one confirmation dialog, because the
 * head's "⋯" asks the same question and there must be one dialog in the top layer, not two.
 */
@Component({
    selector: 'lg-chat-history',
    imports: [Icon, TranslocoPipe],
    templateUrl: './chat-history.html',
    styleUrl: './chat-history.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: {class: 'lg-chat-history'},
})
export class ChatHistory {
    protected readonly store = inject(ChatStore);
    private readonly dispatch = injectDispatch(chatEvents);
    private readonly transloco = inject(TranslocoService);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);

    /** A row's "Delete" was chosen; the panel's dialog asks. */
    readonly deleteAsked = output<DeleteAsk>();

    protected readonly titleMax = TITLE_MAX;
    /** Ids unique per instance: the rail and the list are never drawn together, but nothing should rely on that. */
    protected readonly uid = `lg-chat-h${++instances}`;
    private readonly zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    protected readonly groups = computed(() => groupByActivity(this.store.conversations(), new Date(), this.zone));
    /** The row whose title is an input right now. */
    protected readonly editing = signal<number | null>(null);
    /** A search that came back empty: one line naming the words, and the groups gone with it. */
    protected readonly noMatch = computed(
        () => this.store.resultQuery() !== '' && this.store.conversations().length === 0 && !this.store.listLoading(),
    );
    protected readonly empty = computed(() => this.store.conversations().length === 0 && this.store.resultQuery() === '');

    protected menuId(id: number): string {
        return `${this.uid}-menu-${id}`;
    }

    protected moreId(id: number): string {
        return `${this.uid}-more-${id}`;
    }

    protected timeOf(conversation: ConversationSummary, group: TimeGroupKey): string {
        return activityLabel(activityOf(conversation), group, this.transloco.getActiveLang(), this.zone);
    }

    protected activity(conversation: ConversationSummary): string {
        return activityOf(conversation);
    }

    protected open(id: number): void {
        this.dispatch.openRequested(id);
    }

    protected search(event: Event): void {
        this.dispatch.searchChanged((event.target as HTMLInputElement).value);
    }

    protected clearSearch(): void {
        this.dispatch.searchChanged('');
        this.host.nativeElement.querySelector<HTMLInputElement>('.lg-chat-search-input')?.focus();
    }

    /**
     * The popover sits in the top layer, whose containing block is the viewport, so it is placed
     * from the button's box just before the platform opens it — right-aligned under the "⋯".
     */
    protected place(event: MouseEvent, id: number): void {
        const button = event.currentTarget as HTMLElement;
        const menu = document.getElementById(this.menuId(id));
        if (menu === null) return;
        const box = button.getBoundingClientRect();
        menu.style.top = `${box.bottom + 4}px`;
        menu.style.right = `${Math.max(8, window.innerWidth - box.right)}px`;
    }

    protected startRename(id: number): void {
        this.closeMenu(id);
        this.editing.set(id);
        afterNextRender(
            () => {
                const input = this.host.nativeElement.querySelector<HTMLInputElement>('.lg-chat-rename');
                input?.focus();
                input?.select();
            },
            {injector: this.injector},
        );
    }

    /** Enter saves, Escape puts the old title back; focus returns to the row's "⋯" either way. */
    protected renameKey(event: KeyboardEvent, conversation: ConversationSummary): void {
        if (event.key === 'Enter') {
            event.preventDefault();
            this.commit(event, conversation);
        } else if (event.key === 'Escape') {
            event.preventDefault();
            event.stopPropagation();
            this.editing.set(null);
            this.focusMore(conversation.id);
        }
    }

    /** Blur saves too; a rename already settled by Enter or Escape is not sent twice. */
    protected commit(event: Event, conversation: ConversationSummary): void {
        if (this.editing() !== conversation.id) return;
        this.editing.set(null);
        const title = (event.target as HTMLInputElement).value.trim();
        if (title !== conversation.title) this.dispatch.renameRequested({id: conversation.id, title});
        if (event.type !== 'blur') this.focusMore(conversation.id);
    }

    protected askDelete(conversation: ConversationSummary): void {
        this.closeMenu(conversation.id);
        const rows = [...this.host.nativeElement.querySelectorAll<HTMLElement>('.lg-chat-row')];
        const at = rows.findIndex((row) => row.dataset['id'] === String(conversation.id));
        const next = rows[at + 1] ?? rows[at - 1] ?? this.host.nativeElement.querySelector<HTMLElement>('.lg-chat-search-input');
        this.deleteAsked.emit({
            id: conversation.id,
            title: conversation.title,
            back: document.getElementById(this.moreId(conversation.id)),
            next,
        });
    }

    private closeMenu(id: number): void {
        const menu = document.getElementById(this.menuId(id));
        if (menu?.matches?.(':popover-open')) menu.hidePopover?.();
    }

    private focusMore(id: number): void {
        afterNextRender(() => document.getElementById(this.moreId(id))?.focus(), {injector: this.injector});
    }
}
