import { ChangeDetectionStrategy, Component, OnInit } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { injectDispatch } from '@ngrx/signals/events';
import { statusEvents } from '@core/store/status.events';
import { AppHeader } from '../app-header/app-header';
import { AppNav } from '../app-nav/app-nav';

@Component({
  selector: 'lg-app-shell',
  imports: [AppHeader, AppNav, RouterOutlet],
  templateUrl: './app-shell.html',
  styleUrl: './app-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppShell implements OnInit {
  private readonly dispatch = injectDispatch(statusEvents);

  ngOnInit(): void {
    this.dispatch.opened();
  }
}
