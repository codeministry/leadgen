import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {Observable} from 'rxjs';
import {LlmBudgetView} from '@core/model/llm-budget';

/** `/api/v1/llm` — what the model side of the tool has spent. Read only. */
@Injectable({providedIn: 'root'})
export class LlmApi {
    private readonly http = inject(HttpClient);

    budget(): Observable<LlmBudgetView> {
        return this.http.get<LlmBudgetView>('/api/v1/llm/budget');
    }
}
