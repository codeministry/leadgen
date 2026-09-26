import {inject} from '@angular/core';
import {signalStore, withState} from '@ngrx/signals';
import {Events, on, withEventHandlers, withReducer} from '@ngrx/signals/events';
import {catchError, map, of, switchMap} from 'rxjs';
import {LlmApi} from '@core/api/llm.api';
import {LlmBudgetView} from '@core/model/llm-budget';
import {llmBudgetEvents} from './llm-budget.events';

interface LlmBudgetState {
    budget: LlmBudgetView | null;
}

/**
 * Today's model calls against the ceiling, for the run status sheet.
 *
 * Asked for by the screen that shows it, never on its own: a request on every run or every
 * refresh would reach every screen and every spec that loads a run, for a figure one panel reads.
 * A failed read leaves `null`, so the panel says nothing rather than claiming zero calls.
 */
export const LlmBudgetStore = signalStore(
    {providedIn: 'root'},
    withState<LlmBudgetState>({budget: null}),
    withReducer(on(llmBudgetEvents.loaded, ({payload}) => ({budget: payload}))),
    withEventHandlers(() => {
        const events = inject(Events);
        const api = inject(LlmApi);

        return [
            // `switchMap`: a newer question makes an older answer irrelevant.
            events.on(llmBudgetEvents.requested).pipe(
                switchMap(() =>
                    api.budget().pipe(
                        map((budget) => llmBudgetEvents.loaded(budget)),
                        catchError(() => of(llmBudgetEvents.loaded(null))),
                    ),
                ),
            ),
        ];
    }),
);
