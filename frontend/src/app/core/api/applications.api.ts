import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ApplicationEvent,
  ApplicationUpdate,
  ApplicationView,
  PipelineLane,
} from '@core/model/application';

/** `/api/applications` — the first write endpoint in this application. */
@Injectable({ providedIn: 'root' })
export class ApplicationsApi {
  private readonly http = inject(HttpClient);

  board(): Observable<readonly ApplicationView[]> {
    return this.http.get<readonly ApplicationView[]>('/api/applications');
  }

  lanes(): Observable<readonly PipelineLane[]> {
    return this.http.get<readonly PipelineLane[]>('/api/applications/lanes');
  }

  history(id: number): Observable<readonly ApplicationEvent[]> {
    return this.http.get<readonly ApplicationEvent[]>(`/api/applications/${id}/history`);
  }

  update(id: number, update: ApplicationUpdate): Observable<ApplicationView> {
    return this.http.patch<ApplicationView>(`/api/applications/${id}`, update);
  }
}
