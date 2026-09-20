import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {Observable} from 'rxjs';
import {
  ApplicationEvent,
  ApplicationUpdate,
  ApplicationView,
  PipelineLane,
  TransitionMap,
} from '@core/model/application';

/** `/api/applications` — the first write endpoint in this application. */
@Injectable({providedIn: 'root'})
export class ApplicationsApi {
    private readonly http = inject(HttpClient);

    board(): Observable<readonly ApplicationView[]> {
        return this.http.get<readonly ApplicationView[]>('/api/applications');
    }

    lanes(): Observable<readonly PipelineLane[]> {
        return this.http.get<readonly PipelineLane[]>('/api/applications/lanes');
    }

  /** What each state may move to, so the picker can grey out what the endpoint refuses. */
  transitions(): Observable<TransitionMap> {
    return this.http.get<TransitionMap>('/api/applications/transitions');
  }

    history(id: number): Observable<readonly ApplicationEvent[]> {
        return this.http.get<readonly ApplicationEvent[]>(`/api/applications/${id}/history`);
    }

    update(id: number, update: ApplicationUpdate): Observable<ApplicationView> {
        return this.http.patch<ApplicationView>(`/api/applications/${id}`, update);
    }
}
