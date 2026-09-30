import {ChangeDetectionStrategy, Component, computed, inject, signal} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {RouterLink} from '@angular/router';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {RUN_STATUS} from '@core/model/workflow';
import {IngestStore} from '@core/store/ingest.store';
import {injectTick} from '@core/time/tick';
import {AgoPipe} from '@shared/date/ago.pipe';
import {Badge, BadgeTone} from '@shared/badge/badge';
import {Icon} from '@shared/icon/icon';
import {RunControl} from '../run-control/run-control';

/**
 * The run, from every screen (operator, 2026-09-30): one icon in the header whose colour says
 * whether a pass is going, and a popover with a few lines about it and the control that starts
 * the next one.
 *
 * <p>The popover is the short form of the status sheet on the workflow screen — where the pass
 * stands, a bar, how long it has been going; or how the last one ended — and links there for the
 * rest. Its figures come from `CurrentRunView` alone, not from the workflow's stage order: the
 * header has no workflow loaded, and asking for one on every screen to draw a bar would be the
 * wrong trade.
 *
 * <p>The run control is rendered only while the popover is open. It creates the scoring-model
 * store, and a closed header that did so would ask for the model list on every screen.
 */
@Component({
    selector: 'lg-run-status',
    imports: [AgoPipe, Badge, Icon, RouterLink, RunControl, TranslocoPipe],
    templateUrl: './run-status.html',
    styleUrl: './run-status.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunStatus {
    protected readonly ingest = inject(IngestStore);
    private readonly tick = injectTick();
    private readonly transloco = inject(TranslocoService);
    private readonly lang = toSignal(this.transloco.langChanges$, {initialValue: this.transloco.getActiveLang()});

    /** The id the workflow screen's status sheet answers to, for the popover's link. */
    protected readonly runStatus = RUN_STATUS;

    /** Mirrored from the panel's own `toggle` event, like the settings popover beside it. */
    protected readonly open = signal(false);

    protected onToggle(event: Event): void {
        this.open.set((event as ToggleEvent).newState === 'open');
    }

    /** Only a finished run can have failed; a pass in flight outranks it, as on the workflow chip. */
    protected readonly failed = computed((): boolean =>
        this.ingest.current() === null && this.ingest.lastRun()?.status === 'FAILED',
    );

    /** The button's name and tooltip: the same sentences the workflow screen's status chip speaks. */
    protected readonly label = computed((): string => {
        this.lang();
        const run = this.ingest.current();
        if (run !== null) {
            return run.stagePosition && run.stageTotal
                ? this.transloco.translate('rules.status.ariaRunning', {position: run.stagePosition, total: run.stageTotal})
                : this.transloco.translate('shell.run.running');
        }
        const finishedAt = this.finishedAt();
        if (finishedAt === null) {
            return this.transloco.translate('shell.run.idle');
        }
        const key = this.failed() ? 'rules.status.ariaFailed' : 'rules.status.ariaFinished';
        return this.transloco.translate(key, {time: finishedAt});
    });

    protected readonly startedAt = computed((): string | null => {
        const at = instant(this.ingest.current()?.startedAt);
        return at === null ? null : new Intl.DateTimeFormat(this.lang(), {timeStyle: 'short'}).format(at);
    });

    /** Seconds since the pass in flight started; read only while one is, so the clock costs nothing idle. */
    protected readonly elapsed = computed((): number | null => {
        const at = instant(this.ingest.current()?.startedAt);
        return at === null ? null : Math.max(0, Math.floor((this.tick() - at.getTime()) / 1000));
    });

    /** Stages behind the running one: step 3 of 9 means two are done. */
    protected readonly done = computed((): number | null => {
        const position = this.ingest.current()?.stagePosition ?? null;
        return position === null ? null : Math.max(0, position - 1);
    });

    /** When the last run finished: a time of day today, a date with it otherwise. */
    protected readonly finishedAt = computed((): string | null => {
        const at = instant(this.ingest.lastRun()?.finishedAt);
        if (at === null) {
            return null;
        }
        const today = at.toDateString() === new Date().toDateString();
        return new Intl.DateTimeFormat(this.lang(), today ? {timeStyle: 'short'} : {dateStyle: 'short', timeStyle: 'short'}).format(at);
    });

    /** How long the last run took, in seconds; null without both ends. */
    protected readonly took = computed((): number | null => {
        const run = this.ingest.lastRun();
        if (!run?.startedAt || !run.finishedAt) {
            return null;
        }
        const seconds = Math.round((Date.parse(run.finishedAt) - Date.parse(run.startedAt)) / 1000);
        return Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
    });

    /** From a literal map: a class assembled at runtime is one Tailwind never emits. */
    protected readonly statusTone = computed((): BadgeTone => {
        const status = this.ingest.lastRun()?.status ?? '';
        if (status === 'FAILED') return 'error';
        if (status === 'AWAITING_BATCH') return 'accent';
        return status === 'COMPLETE' ? 'success' : 'neutral';
    });

    /** Whole seconds as `m:ss`, or `n s` under a minute — the grain of the workflow screen's sheet. */
    protected clock(seconds: number): string {
        if (seconds < 60) {
            return `${seconds} s`;
        }
        return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
    }

    /** Following the link to the full sheet closes the popover behind it. */
    protected close(panel: HTMLElement): void {
        panel.hidePopover?.();
    }
}

/**
 * A timestamp as a date, or null when there is none or it does not parse. `Intl` throws a
 * RangeError on an invalid date, and one missing field in a payload would take the whole header
 * down with it rather than leave a line out.
 */
function instant(value: string | null | undefined): Date | null {
    if (!value) return null;
    const at = new Date(value);
    return Number.isNaN(at.getTime()) ? null : at;
}
