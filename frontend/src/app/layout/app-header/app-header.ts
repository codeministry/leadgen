import {ChangeDetectionStrategy, Component, computed, inject, signal, viewChild} from '@angular/core';
import {RouterLink} from '@angular/router';
import {injectDispatch} from '@ngrx/signals/events';
import {ingestEvents} from '@core/store/ingest.events';
import {IngestStore} from '@core/store/ingest.store';
import {scoringModelEvents} from '@core/store/scoring-model.events';
import {ScoringModelStore} from '@core/store/scoring-model.store';
import {StatusStore} from '@core/store/status.store';
import {BrandMark} from '@shared/brand-mark/brand-mark';
import {AppNav} from '../app-nav/app-nav';
import {Icon} from '@shared/icon/icon';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {LanguageToggle} from '../language-toggle/language-toggle';
import {ThemeToggle} from '../theme-toggle/theme-toggle';
import {HelpDrawer} from '../help-drawer/help-drawer';
import {RunConfirm} from './run-confirm/run-confirm';

@Component({
    selector: 'lg-app-header',
  imports: [AppNav, BrandMark, HelpDrawer, Icon, RouterLink, RunConfirm, ThemeToggle, LanguageToggle, TranslocoPipe],
    templateUrl: './app-header.html',
    styleUrl: './app-header.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppHeader {
    protected readonly status = inject(StatusStore);
    protected readonly ingest = inject(IngestStore);
    protected readonly models = inject(ScoringModelStore);
    private readonly ingestDispatch = injectDispatch(ingestEvents);
    private readonly modelDispatch = injectDispatch(scoringModelEvents);

  /**
   * Mirrored from the panel's own `toggle` event rather than tracked on the click, so a
   * light dismiss and an Escape are as visible to a screen reader as the button is.
   */
  protected readonly settingsOpen = signal(false);

  protected onSettingsToggle(event: Event): void {
    this.settingsOpen.set((event as ToggleEvent).newState === 'open');
    }

  private readonly transloco = inject(TranslocoService);

  /**
   * What the run is doing, beside the button.
   *
   * <p>Assembled here and not in the template, because the number sits in a different place
   * in every language and the catalog is where that belongs. The stage name itself is the
   * server's and stays English, exactly like a score reason or a filter-stage label.
   */
  protected readonly runStage = computed(() => {
    const run = this.ingest.current();
    if (run === null) {
      return '';
    }
    if (run.stage === null || run.stagePosition === null || run.stageTotal === null) {
      // The moment between opening the row and entering the first stage. Short, and
      // real: a reader who catches it should see something rather than an empty gap.
      return this.transloco.translate('shell.runStageUnknown');
    }
    return this.transloco.translate('shell.runStage', {
      stage: run.stage,
      position: run.stagePosition,
      total: run.stageTotal,
    });
  });

  /**
   * Why the button is refusing. A disabled control with no reason is worse than one that
   * says no, and this is the sentence that used to arrive as a 409 nobody opened.
   */
  protected readonly runTitle = computed(() => {
    const run = this.ingest.current();
    if (run === null) {
      return null;
    }
    const time = new Intl.DateTimeFormat(this.transloco.getActiveLang(), {timeStyle: 'short'}).format(
      new Date(run.startedAt),
    );
    return run.stage === null
      ? this.transloco.translate('shell.runSince', {time})
      : this.transloco.translate('shell.runSinceStage', {time, stage: run.stage});
  });

    private readonly runConfirm = viewChild.required(RunConfirm);

    /** The button only asks; the run starts from the dialog's confirm (ISC-318). */
    protected askToRun(button: HTMLElement): void {
        this.runConfirm().open(button);
    }

    /**
     * Reading the sources is a pipeline action, not a dashboard one, so it lives here. Reached
     * only through the confirmation.
     */
    protected runIngest(): void {
        this.ingestDispatch.requested();
    }

    /**
     * The choice reaches the request through `ScoringModelStore`, not through the run event:
     * the rescore button on the offer detail has to ask the same judge, and two components
     * handing over a model would be two places that can disagree about which one is current.
     */
    protected chooseModel(event: Event): void {
        this.modelDispatch.chosen((event.target as HTMLSelectElement).value);
    }
}
