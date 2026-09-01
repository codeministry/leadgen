import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { FunnelView } from '@core/model/funnel';
import { ShortlistEntry } from '@core/model/shortlist-entry';

/**
 * `/api/offers` — what survived the filter, ranked, with the reasons behind each score.
 *
 * The whole list comes down in one request and the browser filters it in the query
 * string. That is what makes a filtered shortlist survive a reload and be shareable as a
 * link; a server-side filter would be a second implementation of the same rules.
 */
@Injectable({ providedIn: 'root' })
export class ShortlistApi {
  private readonly http = inject(HttpClient);

  load(): Observable<readonly ShortlistEntry[]> {
    return this.http.get<readonly ShortlistEntry[]>('/api/offers');
  }

  /** What the filter did to the whole archive, stage by stage. */
  funnel(): Observable<FunnelView> {
    return this.http.get<FunnelView>('/api/offers/funnel');
  }

  /**
   * One offer, by id. Not taken from the list: the detail has to work on a reload and on
   * an offer the filter rejected, and neither is in the shortlist.
   */
  one(id: number): Observable<ShortlistEntry> {
    return this.http.get<ShortlistEntry>(`/api/offers/${id}`);
  }
}
