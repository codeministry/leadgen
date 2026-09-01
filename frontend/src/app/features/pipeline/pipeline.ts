import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { injectDispatch } from '@ngrx/signals/events';
import { ApplicationStatus, ApplicationView } from '@core/model/application';
import { applicationEvents } from '@core/store/applications.events';
import { ApplicationsStore } from '@core/store/applications.store';
import { Badge } from '@shared/badge/badge';
import { EmptyState } from '@shared/empty-state/empty-state';
import { Icon } from '@shared/icon/icon';
import { PageHeader } from '@shared/page-header/page-header';
import { Score } from '@shared/score/score';
import { StatusPicker } from '@shared/status-picker/status-picker';

@Component({
  selector: 'lg-pipeline',
  imports: [Badge, EmptyState, Icon, PageHeader, Score, StatusPicker],
  templateUrl: './pipeline.html',
  styleUrl: './pipeline.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Pipeline implements OnInit {
  private readonly dispatch = injectDispatch(applicationEvents);
  protected readonly store = inject(ApplicationsStore);

  ngOnInit(): void {
    this.dispatch.opened();
  }

  /**
   * The picker sends a string because `shared/` does not know the eleven states. The
   * cast is safe for exactly that reason: the options it was given came from here.
   */
  protected move(application: ApplicationView, status: string): void {
    this.dispatch.changed({
      id: application.id,
      update: { status: status as ApplicationStatus },
    });
  }

  protected toneFor(status: ApplicationStatus): 'success' | 'error' | 'ghost' {
    if (status === 'WON') {
      return 'success';
    }
    return status === 'LOST' || status === 'REJECTED' || status === 'EXPIRED' ? 'error' : 'ghost';
  }
}
