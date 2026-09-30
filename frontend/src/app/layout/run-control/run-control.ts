import {ChangeDetectionStrategy, Component, computed, inject, viewChild} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {injectDispatch} from '@ngrx/signals/events';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {ingestEvents} from '@core/store/ingest.events';
import {IngestStore} from '@core/store/ingest.store';
import {scoringModelEvents} from '@core/store/scoring-model.events';
import {ScoringModelStore} from '@core/store/scoring-model.store';
import {Icon} from '@shared/icon/icon';
import {RunConfirm} from './run-confirm/run-confirm';

/** Numbers each instance's select, so its label still names exactly one control. */
let nextSelectId = 0;

/**
 * Starting a run by hand, and choosing the judge it asks: the scoring-model select and Run ingest
 * (operator, 2026-09-27).
 *
 * <p>It lives in two places: on the workflow screen, directly above the status chip that follows
 * the run it starts, and in the header's run-status popover, so a run can be started from any
 * screen (operator, 2026-09-30). Both can be on screen at once, which is why the select's id is
 * per instance. While a pass is going the button stays and is disabled, with the step in its
 * title (ISC-318).
 *
 * <p>No request leaves from here: the click dispatches `ingestEvents.requested`, and the POST is
 * `IngestStore`'s, in core — the workflow screen itself holds no write call (ISC-419).
 */
@Component({
    selector: 'lg-run-control',
    imports: [Icon, RunConfirm, TranslocoPipe],
    templateUrl: './run-control.html',
    styleUrl: './run-control.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunControl {
    protected readonly ingest = inject(IngestStore);
    protected readonly models = inject(ScoringModelStore);
    private readonly ingestDispatch = injectDispatch(ingestEvents);
    private readonly modelDispatch = injectDispatch(scoringModelEvents);
    private readonly transloco = inject(TranslocoService);
    private readonly lang = toSignal(this.transloco.langChanges$, {initialValue: this.transloco.getActiveLang()});
    private readonly runConfirm = viewChild.required(RunConfirm);
    protected readonly selectId = `scoring-model-${nextSelectId++}`;

    /**
     * Why the button is refusing. Disabled on `busy` and not on `running`: `running` says only
     * whether *this* browser is waiting on its own request, so a nightly pass left the button
     * enabled and the click came back as a 409 nobody saw. A disabled control with no reason is
     * worse than one that says no, so the title carries when the pass began and its step; the
     * step name is the server's and stays English, like a score reason.
     */
    protected readonly runTitle = computed(() => {
        const run = this.ingest.current();
        if (run === null) {
            return null;
        }
        const lang = this.lang();
        const time = new Intl.DateTimeFormat(lang, {timeStyle: 'short'}).format(new Date(run.startedAt));
        return run.stage === null
            ? this.transloco.translate('rules.runControl.since', {time})
            : this.transloco.translate('rules.runControl.sinceStage', {time, stage: run.stage});
    });

    /** The button only asks; the run starts from the dialog's confirm (ISC-318). */
    protected askToRun(button: HTMLElement): void {
        this.runConfirm().open(button);
    }

    /** Reached only through the confirmation. */
    protected runIngest(): void {
        this.ingestDispatch.requested();
    }

    /**
     * The choice reaches the request through `ScoringModelStore`, not through the run event: the
     * rescore button on the offer detail has to ask the same judge, and two components handing
     * over a model would be two places that can disagree about which one is current.
     */
    protected chooseModel(event: Event): void {
        this.modelDispatch.chosen((event.target as HTMLSelectElement).value);
    }
}
