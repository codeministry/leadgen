import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { SHORTLIST_FIXTURE } from '@core/fixtures/shortlist.fixture';
import { ShortlistEntry } from '@core/model/shortlist-entry';

/**
 * FIXTURE — replace with the real endpoint (order of work, steps 5-9).
 *
 * The seam a real `GET /api/shortlist` will occupy. Everything above it already
 * treats the answer as asynchronous and failable, so swapping in `HttpClient`
 * touches this file and nothing else.
 */
@Injectable({ providedIn: 'root' })
export class ShortlistApi {
  load(): Observable<readonly ShortlistEntry[]> {
    return of(SHORTLIST_FIXTURE);
  }
}
