import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { injectDispatch } from '@ngrx/signals/events';
import { statusEvents } from '@core/store/status.events';
import { StatusStore } from '@core/store/status.store';

@Component({
  selector: 'lg-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App implements OnInit {
  protected readonly store = inject(StatusStore);
  private readonly dispatch = injectDispatch(statusEvents);

  ngOnInit(): void {
    this.dispatch.opened();
  }
}
