import {ChangeDetectionStrategy, Component, computed, inject, signal} from '@angular/core';
import {RouterLink} from '@angular/router';
import {injectDispatch} from '@ngrx/signals/events';
import {toSignal} from '@angular/core/rxjs-interop';
import {chatEvents} from '@core/store/chat.events';
import {ChatStore} from '@core/store/chat.store';
import {StatusStore} from '@core/store/status.store';
import {BrandMark} from '@shared/brand-mark/brand-mark';
import {AppNav} from '../app-nav/app-nav';
import {Icon} from '@shared/icon/icon';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {LanguageToggle} from '../language-toggle/language-toggle';
import {ThemeToggle} from '../theme-toggle/theme-toggle';
import {HelpDrawer} from '../help-drawer/help-drawer';

/**
 * The chrome on every screen: the brand, the navigation, the chat, settings and help.
 *
 * <p>Run ingest and the model choice are not here any more (operator, 2026-09-27). Starting a run
 * is a pipeline action, so it sits on the workflow screen, directly above the status chip that
 * follows the run it starts — `features/rules/run-control/`.
 */
@Component({
    selector: 'lg-app-header',
  imports: [AppNav, BrandMark, HelpDrawer, Icon, RouterLink, ThemeToggle, LanguageToggle, TranslocoPipe],
    templateUrl: './app-header.html',
    styleUrl: './app-header.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppHeader {
    protected readonly status = inject(StatusStore);

  /**
   * Mirrored from the panel's own `toggle` event rather than tracked on the click, so a
   * light dismiss and an Escape are as visible to a screen reader as the button is.
   */
  protected readonly settingsOpen = signal(false);

  protected onSettingsToggle(event: Event): void {
    this.settingsOpen.set((event as ToggleEvent).newState === 'open');
    }

  private readonly transloco = inject(TranslocoService);

    protected readonly chat = inject(ChatStore);
    private readonly chatDispatch = injectDispatch(chatEvents);

    /** A turn still being written while the drawer is shut: the dot, and a word for it in the name. */
    protected readonly chatBusy = computed(() => this.chat.streaming() && this.chat.view() === 'closed');

    protected readonly chatLabel = computed(() => {
        this.lang();
        const open = this.transloco.translate('chat.open');
        return this.chatBusy() ? `${open}, ${this.transloco.translate('chat.busyLabel')}` : open;
    });

    private readonly lang = toSignal(this.transloco.langChanges$, {initialValue: this.transloco.getActiveLang()});

    constructor() {
        // Asked once per page: absent, the header draws nothing and nothing else of the chat is asked.
        if (this.chat.present() === null) this.chatDispatch.capabilityRequested();
    }

    /** Opens the last conversation, or a new one; never pins an offer (that is the detail's button). */
    protected toggleChat(): void {
        if (this.chat.view() === 'closed') this.chatDispatch.reopenRequested();
        else this.chatDispatch.closeRequested();
    }
}
