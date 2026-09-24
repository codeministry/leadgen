import {HttpClient, HttpParams} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {Observable} from 'rxjs';
import {PromptView} from '@core/model/prompt-view';
import {RulesView} from '@core/model/rules-view';
import {ScoringModels} from '@core/model/scoring-models';
import {SourceDetail} from '@core/model/source-detail';
import {SourcesView} from '@core/model/source-summary';
import {WorkflowView} from '@core/model/workflow';

/**
 * `/api/v1/sources`, `/api/v1/rules`, `/api/v1/prompts` and `/api/v1/workflow` — the configuration as the screens read it.
 *
 * Read-only, and deliberately so: the four YAML files are the source of truth and they
 * are hot-reloaded, so a write path here would mean two ways to change the same thing.
 */
@Injectable({providedIn: 'root'})
export class ConfigApi {
    private readonly http = inject(HttpClient);

  sources(): Observable<SourcesView> {
    return this.http.get<SourcesView>('/api/v1/sources');
  }

  /**
   * One source, opened: the block that defines it and the runs it has had.
   *
   * A request of its own rather than a fatter list. `/api/v1/sources` is refetched whenever a
   * run finishes and whenever the tab comes back to the front, and file text has no business
   * on that path for a panel most readers never open.
   *
   * The text arrives masked. Masking happens on the server, or the unmasked file would be in
   * the network tab, in the dev server's proxy log and in any reverse proxy in front of it.
   */
  source(id: string, runs: number): Observable<SourceDetail> {
    return this.http.get<SourceDetail>(`/api/v1/sources/${encodeURIComponent(id)}`, {
      params: new HttpParams().set('runs', runs),
    });
    }

    rules(): Observable<RulesView> {
        return this.http.get<RulesView>('/api/v1/rules');
    }

    /**
     * The prompts as this configuration renders them. Beside the rules because they are the
     * other half of the same answer: the weights decide what a factor is worth, the prompt
     * decides what the model is asked.
     */
    prompts(): Observable<readonly PromptView[]> {
        return this.http.get<readonly PromptView[]>('/api/v1/prompts');
    }

    /**
     * The pipeline as phases and stages, each stage with the keys it reads. The rules screen
     * draws its rail and its detail from this; nothing on the pipeline side reads it.
     */
    workflow(): Observable<WorkflowView> {
        return this.http.get<WorkflowView>('/api/v1/workflow');
    }

    /** What the select beside the run button may offer, and what a run takes by default. */
    scoringModels(): Observable<ScoringModels> {
        return this.http.get<ScoringModels>('/api/v1/scoring-models');
    }
}
