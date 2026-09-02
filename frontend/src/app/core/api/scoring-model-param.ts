import { HttpParams } from '@angular/common/http';

/**
 * The chosen judge as a query parameter, or none at all.
 *
 * Shared by the two endpoints that spend money — the run and the single-offer rescore —
 * so they cannot disagree about how the choice is spelled or about what "no choice" is.
 * Omitted rather than sent empty: an empty parameter would have to mean the default on
 * the server as well, which is a second way of saying the same thing.
 */
export function scoringModelParams(model: string | null): HttpParams {
  return model === null ? new HttpParams() : new HttpParams().set('model', model);
}
