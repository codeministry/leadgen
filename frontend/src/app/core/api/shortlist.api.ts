import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { FunnelView } from '@core/model/funnel';
import { ShortlistEntry } from '@core/model/shortlist-entry';
import { ShortlistFilters, ShortlistPage } from '@core/model/shortlist-page';
import { scoringModelParams } from './scoring-model-param';

/**
 * `/api/offers` — what survived the filter, ranked, with the reasons behind each score.
 *
 * The list comes down a page at a time and the filters travel with the request. The query
 * string still holds them, so a filtered shortlist survives a reload and is shareable as a
 * link — only the deciding happens in SQL, because a page of a browser-filtered list is not
 * a page of anything.
 */
@Injectable({ providedIn: 'root' })
export class ShortlistApi {
  private readonly http = inject(HttpClient);

  /**
   * One page of the shortlist. The filters go to the server because a page of a
   * browser-filtered list is not a page of anything, and because the band boundaries are
   * the configured thresholds — which the browser has no business restating.
   */
  page(filters: ShortlistFilters, cursor: string | null): Observable<ShortlistPage> {
    let params = new HttpParams();
    if (filters.q.trim() !== '') {
      params = params.set('q', filters.q.trim());
    }
    if (filters.band !== '' && filters.band !== 'all') {
      params = params.set('band', filters.band);
    }
    if (filters.portal !== '') {
      params = params.set('portal', filters.portal);
    }
    if (filters.archived) {
      params = params.set('archived', 'true');
    }
    if (cursor !== null) {
      params = params.set('cursor', cursor);
    }
    return this.http.get<ShortlistPage>('/api/offers', { params });
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

  /**
   * Take one offer off the working list, or put it back.
   *
   * A PATCH on the offer itself, answering with the whole entry: the store replaces its
   * row with what the server stored rather than patching its own copy, because the server
   * decides what `archived: false` writes and a locally patched row would disagree with
   * the database until the next reload.
   */
  setArchived(id: number, archived: boolean): Observable<ShortlistEntry> {
    return this.http.patch<ShortlistEntry>(`/api/offers/${id}`, { archived });
  }

  /**
   * Judge this one offer again. A POST because it spends a language-model call and
   * rewrites the score, and it answers with the whole entry rather than the score alone.
   */
  rescore(id: number, model: string | null): Observable<ShortlistEntry> {
    return this.http.post<ShortlistEntry>(`/api/offers/${id}/score`, null, {
      params: scoringModelParams(model),
    });
  }
}
