import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';
import {ListDensity} from './density.model';

export const densityEvents = eventGroup({
  source: 'Density',
  events: {
    /** The stored density, read back at startup. */
    restored: type<ListDensity>(),
    /** The reader picked one of the two. */
    chosen: type<ListDensity>(),
  },
});
