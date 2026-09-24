import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';
import {PromptView} from '@core/model/prompt-view';
import {RulesView} from '@core/model/rules-view';
import {SourceDetail} from '@core/model/source-detail';
import {SourcesView} from '@core/model/source-summary';
import {WorkflowView} from '@core/model/workflow';

/**
 * Two screens, one store, and therefore two `*Opened` events: the sources list and the rules
 * are loaded independently, and only the first of them is invalidated by a finished run.
 */
export const configEvents = eventGroup({
    source: 'Config',
    events: {
        sourcesOpened: type<void>(),
      sourcesLoaded: type<SourcesView>(),
      /** One source was opened, by a click or by a link somebody pasted. */
      sourceOpened: type<string>(),
      sourceLoaded: type<SourceDetail>(),
      /** The panel alone failed. Kept apart from `failed`, which is about the list. */
      sourceFailed: type<string>(),
      /** The panel was closed, which is a navigation back to `/sources`. */
      sourceClosed: type<void>(),
        rulesOpened: type<void>(),
        rulesLoaded: type<RulesView>(),
        promptsLoaded: type<readonly PromptView[]>(),
        /** The pipeline as phases and stages, the rules screen's rail and detail. */
        workflowLoaded: type<WorkflowView>(),
      /**
       * One per screen, because the state they write is one per screen. A single `failed`
       * meant an error raised while the rules loaded turned up on the sources screen after
       * a navigation.
       */
      sourcesFailed: type<string>(),
      rulesFailed: type<string>(),
    },
});
