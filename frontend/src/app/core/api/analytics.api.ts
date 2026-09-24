import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {Observable} from 'rxjs';
import {AnalyticsSummary, AnalyticsView} from '@core/model/analytics';

/**
 * `/api/v1/analytics` — every series the screen draws, in one answer.
 *
 * <p>One request rather than six, because the screen shows one moment: a run finishing
 * between the second call and the fifth would leave a funnel that does not match a
 * histogram, with nothing on the page saying why.
 *
 * <p>`/api/v1/analytics/summary` is the dashboard's read (spec 006): the same moment, three
 * small groups, so the dashboard never asks for the whole thing.
 */
@Injectable({providedIn: 'root'})
export class AnalyticsApi {
    private readonly http = inject(HttpClient);

    load(): Observable<AnalyticsView> {
        return this.http.get<AnalyticsView>('/api/v1/analytics');
    }

    summary(): Observable<AnalyticsSummary> {
        return this.http.get<AnalyticsSummary>('/api/v1/analytics/summary');
    }
}
