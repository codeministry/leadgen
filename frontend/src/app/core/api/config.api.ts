import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {Observable} from 'rxjs';
import {PromptView} from '@core/model/prompt-view';
import {RulesView} from '@core/model/rules-view';
import {ScoringModels} from '@core/model/scoring-models';
import {SourceSummary} from '@core/model/source-summary';

/**
 * `/api/sources`, `/api/rules` and `/api/prompts` — the configuration as the screens read it.
 *
 * Read-only, and deliberately so: the four YAML files are the source of truth and they
 * are hot-reloaded, so a write path here would mean two ways to change the same thing.
 */
@Injectable({providedIn: 'root'})
export class ConfigApi {
    private readonly http = inject(HttpClient);

    sources(): Observable<readonly SourceSummary[]> {
        return this.http.get<readonly SourceSummary[]>('/api/sources');
    }

    rules(): Observable<RulesView> {
        return this.http.get<RulesView>('/api/rules');
    }

    /**
     * The prompts as this configuration renders them. Beside the rules because they are the
     * other half of the same answer: the weights decide what a factor is worth, the prompt
     * decides what the model is asked.
     */
    prompts(): Observable<readonly PromptView[]> {
        return this.http.get<readonly PromptView[]>('/api/prompts');
    }

    /** What the select beside the run button may offer, and what a run takes by default. */
    scoringModels(): Observable<ScoringModels> {
        return this.http.get<ScoringModels>('/api/scoring-models');
    }
}
