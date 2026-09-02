import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { RulesView } from '@core/model/rules-view';
import { ScoringModels } from '@core/model/scoring-models';
import { SourceSummary } from '@core/model/source-summary';

/**
 * `/api/sources` and `/api/rules` — the configuration as the screens read it.
 *
 * Read-only, and deliberately so: the four YAML files are the source of truth and they
 * are hot-reloaded, so a write path here would mean two ways to change the same thing.
 */
@Injectable({ providedIn: 'root' })
export class ConfigApi {
  private readonly http = inject(HttpClient);

  sources(): Observable<readonly SourceSummary[]> {
    return this.http.get<readonly SourceSummary[]>('/api/sources');
  }

  rules(): Observable<RulesView> {
    return this.http.get<RulesView>('/api/rules');
  }

  /** What the select beside the run button may offer, and what a run takes by default. */
  scoringModels(): Observable<ScoringModels> {
    return this.http.get<ScoringModels>('/api/scoring-models');
  }
}
