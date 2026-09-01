import { ChangeDetectionStrategy, Component } from '@angular/core';
import { AppShell } from '@layout/app-shell/app-shell';

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
export class App {}
