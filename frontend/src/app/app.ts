import {ChangeDetectionStrategy, Component, computed, effect, inject, untracked} from '@angular/core';
import {injectDispatch} from '@ngrx/signals/events';
import {RefreshStore} from '@core/refresh/refresh.store';
import {chatEvents} from '@core/store/chat.events';
import {ChatStore} from '@core/store/chat.store';
import {IngestStore} from '@core/store/ingest.store';
import {ChatPanel} from '@features/chat/chat-panel/chat-panel';
import {AppShell} from '@layout/app-shell/app-shell';

/**
 * The root exists to mount the shell and nothing else. Renaming its selector
 * means editing `src/index.html` too — every test still passes with a mismatch,
 * and the only symptom is a blank page with no console error.
 */
@Component({
    selector: 'lg-root',
    imports: [AppShell, ChatPanel],
    templateUrl: './app.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
    private readonly chat = inject(ChatStore);
    private readonly dispatch = injectDispatch(chatEvents);
    /** The drawer is open, by the URL's `?chat`: the deferred panel is created then, and stays. */
    protected readonly chatOpen = computed(() => this.chat.present() === true && this.chat.view() !== 'closed');

  /**
   * `RefreshStore` is injected for its existence and for nothing it returns.
   *
   * A `providedIn: 'root'` store is created when something asks for it, and nothing asks
   * for this one: it holds no state and no screen reads it. Its whole job is to watch — a
   * pass ending, the tab coming back after a while — and raise one signal that every other
   * store listens for. Unreferenced it was never constructed, so its handlers never ran and
   * the app went on loading exactly as statically as before. Measured that way: the hint
   * never appeared, and nothing anywhere said why.
   *
   * Here rather than in the shell, because it is about the application being open and not
   * about any layout. In the constructor rather than as a field, because a field nobody
   * reads is a field `noUnusedLocals` refuses.
   */
  constructor() {
    inject(RefreshStore);
    // The same reason for `IngestStore`: its `onInit` starts the run heartbeat, which the
    // run-ended refresh and the run toast both hang on. The header's run control used to create
    // it on every screen; since that control moved to the workflow screen, nothing did anywhere
    // else, and a run ending on /pipeline refreshed nothing and said nothing.
    inject(IngestStore);
    // The model catalog each time the drawer opens, so a connection that went away or came back is
    // seen (spec 025, ISC-511). Here and not in the composer: the drawer stops checking its projected
    // content while closed, so an effect in there never saw it close and asked only on the first open.
    // Not on a page load, and nothing without a chat model (ISC-513).
    let wasOpen = false;
    effect(() => {
      const open = this.chatOpen();
      if (open && !wasOpen) untracked(() => this.dispatch.modelsRequested());
      wasOpen = open;
    });
  }
}
