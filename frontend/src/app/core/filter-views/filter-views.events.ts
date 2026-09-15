import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';
import {FilterView} from './filter-view.model';

export const filterViewEvents = eventGroup({
  source: 'Filter views',
  events: {
    /** What storage held, read back at startup. */
    restored: type<readonly FilterView[]>(),
    /** The reader named the view that is currently on screen. */
    saved: type<{ readonly name: string; readonly query: string }>(),
    /** The reader threw one away. */
    removed: type<string>(),
  },
});
