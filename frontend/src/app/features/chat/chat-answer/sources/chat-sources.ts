import {ChangeDetectionStrategy, Component, computed, input, signal} from '@angular/core';
import {RouterLink} from '@angular/router';
import {TranslocoPipe} from '@jsverse/transloco';
import {ChatSource} from '@core/model/chat';
import {Badge} from '@shared/badge/badge';
import {DayPipe} from '@shared/date/day.pipe';
import {Icon} from '@shared/icon/icon';
import {citeCommands, distinctSources} from '../answer-markdown';

/** Four cards, then "Show all": past that the sources outgrow the answer they sit under. */
const SHOWN = 4;

/**
 * The rows an answer rests on (ISC-430), as cards carrying the same numbers as its pills.
 *
 * <p>One card component across widths: a snap row below 48rem, a grid wider. Each card is one
 * link, and following it keeps `?chat` so the drawer stays beside the row it opened. No score
 * ring: that would bring the signal into the chat, and a cited offer may be archived.
 */
@Component({
    selector: 'lg-chat-sources',
    imports: [Badge, DayPipe, Icon, RouterLink, TranslocoPipe],
    templateUrl: './chat-sources.html',
    styleUrl: './chat-sources.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatSources {
    readonly sources = input<readonly ChatSource[]>([]);

    protected readonly all = computed(() => distinctSources(this.sources()));
    private readonly expanded = signal(false);
    protected readonly shown = computed(() => (this.expanded() ? this.all() : this.all().slice(0, SHOWN)));
    protected readonly hidden = computed(() => this.all().length - this.shown().length);

    protected readonly commands = (source: ChatSource) => citeCommands(source);

    protected expand(): void {
        this.expanded.set(true);
    }
}
