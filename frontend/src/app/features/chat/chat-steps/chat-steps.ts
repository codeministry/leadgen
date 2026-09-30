import {ChangeDetectionStrategy, Component, computed, input} from '@angular/core';
import {TranslocoPipe} from '@jsverse/transloco';
import {ChatStep} from '@core/model/chat';
import {Icon} from '@shared/icon/icon';

/**
 * The tool calls a turn made (ISC-430): each running one as its own row in the AI band, the
 * finished ones folded into a single "Used N tools · M rows" disclosure.
 *
 * <p>Folded, because the finished calls are provenance rather than the answer: the answer's
 * citations and the sources under it already say which rows it rests on, and three lines of
 * "Searched offers" above every reply push the prose out of view. A native `<details>`, so the
 * fold needs no state here and keyboard and screen reader get it for free.
 *
 * <p>The running row is one of the four places `--lg-ai` appears: a model is working right now.
 */
@Component({
    selector: 'lg-chat-steps',
    imports: [Icon, TranslocoPipe],
    templateUrl: './chat-steps.html',
    styleUrl: './chat-steps.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatSteps {
    readonly steps = input<readonly ChatStep[]>([]);

    protected readonly done = computed(() => this.steps().filter((s) => s.state === 'DONE'));
    protected readonly running = computed(() => this.steps().filter((s) => s.state === 'RUNNING'));
    protected readonly rows = computed(() => this.done().reduce((sum, s) => sum + (s.count ?? 0), 0));

    protected seconds(ms: number | null): string | null {
        return ms === null ? null : (ms / 1000).toFixed(1);
    }
}
