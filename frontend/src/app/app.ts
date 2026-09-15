import {ChangeDetectionStrategy, Component, inject} from '@angular/core';
import {RefreshStore} from '@core/refresh/refresh.store';
import {AppShell} from '@layout/app-shell/app-shell';

/**
 * The root exists to mount the shell and nothing else. Renaming its selector
 * means editing `src/index.html` too — every test still passes with a mismatch,
 * and the only symptom is a blank page with no console error.
 */
@Component({
    selector: 'lg-root',
    imports: [AppShell],
    templateUrl: './app.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
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
  }
}
