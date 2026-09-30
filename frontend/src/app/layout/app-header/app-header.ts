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
import {LivingMark} from '@shared/living-mark/living-mark';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {LanguageToggle} from '../language-toggle/language-toggle';
import {ThemeToggle} from '../theme-toggle/theme-toggle';
import {HelpDrawer} from '../help-drawer/help-drawer';
import {UserMenu} from '../user-menu/user-menu';
import {SignedInState} from '@core/auth/signed-in.state';

/**
 * The chrome on every screen: the brand, the navigation, the chat, settings and help.
 *
 * <p>Run ingest and the model choice are not here any more (operator, 2026-09-27). Starting a run
 * is a pipeline action, so it sits on the workflow screen, directly above the status chip that
 * follows the run it starts — `features/rules/run-control/`.
 */
@Component({
    selector: 'lg-app-header',
  imports: [AppNav, BrandMark, HelpDrawer, Icon, LivingMark, RouterLink, ThemeToggle, LanguageToggle, TranslocoPipe, UserMenu],
    templateUrl: './app-header.html',
    styleUrl: './app-header.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: {'(document:keydown)': 'onShortcut($event)'},
})
export class AppHeader {
    protected readonly status = inject(StatusStore);
    protected readonly signedIn = inject(SignedInState);

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

    /** A turn still being written while the drawer is shut: the mark's working frame, and a word for it in the name. */
    protected readonly chatBusy = computed(() => this.chat.streaming() && this.chat.view() === 'closed');

    protected readonly chatLabel = computed(() => {
        this.lang();
        const open = this.transloco.translate('chat.open');
        return this.chatBusy() ? `${open}, ${this.transloco.translate('chat.busyLabel')}` : open;
    });

    private readonly lang = toSignal(this.transloco.langChanges$, {initialValue: this.transloco.getActiveLang()});

    /** ⌘K on macOS, Ctrl+K elsewhere (ISC-461), in the notation `aria-keyshortcuts` takes. */
    protected readonly shortcutKeys = computed(() => (isMac() ? 'Meta+K' : 'Control+K'));

    /**
     * The tooltip names the shortcut on a fine pointer. On a coarse one no tooltip is ever shown, and
     * `aria-keyshortcuts` is what announces it, at every width.
     */
    protected readonly chatTitle = computed(() => {
        this.lang();
        if (!finePointer()) return this.transloco.translate('chat.open');
        return this.transloco.translate('chat.openShortcut', {keys: isMac() ? '⌘K' : 'Ctrl+K'});
    });

    /** The chip inside the button: the shortcut in symbols on a fine pointer, nothing on a coarse one. */
    protected readonly shortcutChip = computed(() => (finePointer() ? (isMac() ? '⌘K' : 'Ctrl K') : null));

    constructor() {
        // Asked once per page: absent, the header draws nothing and nothing else of the chat is asked.
        if (this.chat.present() === null) this.chatDispatch.capabilityRequested();
    }

    /**
     * The shortcut opens the chat from any screen with focus in the composer; with the chat already
     * open it only puts focus back there. Only the platform's own combination is taken from the
     * browser: Ctrl+K on a Mac stays the browser's, and so does every other key.
     */
    protected onShortcut(event: KeyboardEvent): void {
        if (!this.chat.present() || event.key.toLowerCase() !== 'k' || event.altKey || event.shiftKey) return;
        const mac = isMac();
        if (mac ? !event.metaKey || event.ctrlKey : !event.ctrlKey || event.metaKey) return;
        event.preventDefault();
        // Closed, the panel opens and its own focus handover lands in the composer on a fine pointer.
        if (this.chat.view() === 'closed') this.chatDispatch.reopenRequested();
        else document.querySelector<HTMLElement>('.lg-chat-input')?.focus({preventScroll: true});
    }

    /** Opens the last conversation, or a new one; never pins an offer (that is the detail's button). */
    protected toggleChat(): void {
        if (this.chat.view() === 'closed') this.chatDispatch.reopenRequested();
        else this.chatDispatch.closeRequested();
    }
}

/** The Client Hint where the browser offers one, else the older `platform` string. */
function isMac(): boolean {
    if (typeof navigator === 'undefined') return false;
    const hinted = (navigator as Navigator & {userAgentData?: {platform?: string}}).userAgentData?.platform;
    return /mac|iphone|ipad|ipod/i.test(hinted || navigator.platform);
}

function finePointer(): boolean {
    return typeof matchMedia !== 'function' || !matchMedia('(pointer: coarse)').matches;
}
