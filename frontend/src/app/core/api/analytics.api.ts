import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AnalyticsView } from '@core/model/analytics';

/**
 * `/api/analytics` — every series the screen draws, in one answer.
 *
 * <p>One request rather than six, because the screen shows one moment: a run finishing
 * between the second call and the fifth would leave a funnel that does not match a
 * histogram, with nothing on the page saying why.
 */
@Injectable({ providedIn: 'root' })
export class AnalyticsApi {
  private readonly http = inject(HttpClient);

  load(): Observable<AnalyticsView> {
    return this.http.get<AnalyticsView>('/api/analytics');
  }
}
