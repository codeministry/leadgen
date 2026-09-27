import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';
import {LlmBudgetView} from '@core/model/llm-budget';

export const llmBudgetEvents = eventGroup({
    source: 'LLM budget',
    events: {
        requested: type<void>(),
        /** Null when the budget could not be read: the panel then says nothing rather than zero. */
        loaded: type<LlmBudgetView | null>(),
    },
});
