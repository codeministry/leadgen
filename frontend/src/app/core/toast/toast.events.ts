import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';
import {Toast} from './toast.model';

/**
 * The toast's own lifecycle. What *raises* one is not an event here: the store listens to
 * the other stores' answer events — `archived`, `updated`, `finished` — and maps them,
 * so no screen ever dispatches a toast for its own action. The only one of these a
 * component dispatches is `dismissed`, from the close button; `held` and `released` come
 * from the pointer and the focus, and the rest from the store's own timer.
 */
export const toastEvents = eventGroup({
    source: 'Toast',
    events: {
        raised: type<Toast>(),
        /** The close button. */
        dismissed: type<number>(),
        /** The timer ran out. */
        expired: type<number>(),
        /** Under the pointer or holding focus: the timer stops. */
        held: type<number>(),
        /** Neither any more: a fresh timer starts. */
        released: type<number>(),
    },
});
