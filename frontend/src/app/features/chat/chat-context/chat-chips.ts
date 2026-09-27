import {NgTemplateOutlet} from '@angular/common';
import {ChangeDetectionStrategy, Component, computed, input, output} from '@angular/core';
import {TranslocoPipe} from '@jsverse/transloco';
import {ChatContextItem, formatChatCtx, viewFilterCount} from '@core/model/chat';
import {Icon} from '@shared/icon/icon';
import {LgIconName} from '@shared/icon/lucide-icons';

/** Two rows of about three chips in the composer's frame; the rest goes behind "+N". */
const VISIBLE = 6;

let popovers = 0;

interface Chip {
    readonly item: ChatContextItem;
    /** The item in `?chatCtx` form: unique per chip, so it is what the list tracks by. */
    readonly token: string;
    readonly icon: LgIconName;
    readonly key: string;
    readonly params: Record<string, unknown>;
}

function chipOf(item: ChatContextItem): Chip {
    const token = formatChatCtx([item]) ?? '';
    switch (item.kind) {
        case 'OFFER':
            return {item, token, icon: 'file-text', key: 'chat.context.offer', params: {id: item.offerId}};
        case 'SHORTLIST_VIEW':
            return {item, token, icon: 'list-filter', key: 'chat.context.view', params: {count: viewFilterCount(item.query ?? '')}};
        case 'ANALYTICS_WINDOW':
            return {item, token, icon: 'chart-line', key: 'chat.context.window', params: {from: item.from, to: item.to}};
    }
}

/**
 * The pins a conversation is asked under, one neutral chip each (ISC-446, ISC-451): a kind icon,
 * a label and, where the pins can change, a ✕. Neutral on purpose — `base-200` on a `base-300`
 * edge, never the signal (a pinned offer may have been knocked out) and never `--lg-ai`, whose
 * five uses are fixed. Past two rows the rest wait behind a "+N" that opens a native popover
 * listing every pin, so ten pins never push the thread off a phone.
 */
@Component({
    selector: 'lg-chat-chips',
    imports: [Icon, NgTemplateOutlet, TranslocoPipe],
    templateUrl: './chat-chips.html',
    styleUrl: './chat-chips.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatChips {
    readonly items = input.required<readonly ChatContextItem[]>();
    /** Whether a ✕ is offered: the composer decides, because it knows whether the pins can change. */
    readonly removable = input(false);
    readonly removed = output<ChatContextItem>();

    protected readonly chips = computed(() => this.items().map(chipOf));
    protected readonly shown = computed(() => this.chips().slice(0, VISIBLE));
    protected readonly hidden = computed(() => this.chips().length - this.shown().length);
    protected readonly popoverId = `lg-chat-chips-more-${++popovers}`;
}
