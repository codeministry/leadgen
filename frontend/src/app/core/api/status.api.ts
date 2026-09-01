import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** What `GET /api/status` returns. Kept in sync with `StatusController` by hand. */
export interface AppStatus {
  readonly application: string;
  readonly version: string;
}

@Injectable({ providedIn: 'root' })
export class StatusApi {
  private readonly http = inject(HttpClient);

  /** RxJS stays at the I/O boundary; the store bridges into signals. */
  load(): Observable<AppStatus> {
    return this.http.get<AppStatus>('/api/status');
  }
}
