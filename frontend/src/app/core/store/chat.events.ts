import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';
import {ChatEvent, ConversationSummary, ConversationView} from '@core/model/chat';

/**
 * The chat drawer's events.
 *
 * Two kinds, kept apart on purpose. The `*Requested` intents that move the drawer — open, new,
 * list, close, reopen — never change state themselves: they write `?chat` and nothing else, and
 * `routed` is what arrives when the URL says where the drawer stands. That is the house's
 * router-driven state, and it is why a reload and a change of screen land in the same place.
 */
export const chatEvents = eventGroup({
    source: 'Chat',
    events: {
        /** `?chat` as the URL now holds it: an id, `new`, `list`, or `null` when absent. */
        routed: type<string | null>(),

        openRequested: type<number>(),
        /** A fresh conversation, optionally with the offer the drawer was opened from pinned. */
        newRequested: type<{pinnedOfferId: number | null}>(),
        listRequested: type<void>(),
        /** The list read without moving the drawer: the 80rem rail shows it beside an open conversation. */
        conversationsRequested: type<void>(),
        closeRequested: type<void>(),
        /** The header's button: the last conversation opened, or a new one when there is none. */
        reopenRequested: type<void>(),

        capabilityRequested: type<void>(),
        capabilityLoaded: type<boolean>(),

        listLoaded: type<readonly ConversationSummary[]>(),
        listFailed: type<string>(),

        conversationLoaded: type<ConversationView>(),
        /** The 404 for an id in `?chat`: a state the drawer shows, never a failure. */
        conversationMissing: type<number>(),
        conversationFailed: type<{id: number; message: string}>(),

        deleteRequested: type<number>(),
        deleted: type<number>(),
        deleteFailed: type<string>(),

        /** A question for the open conversation, or for a new one created on the way. */
        asked: type<string>(),
        /** The conversation a first question under `?chat=new` created. */
        created: type<ConversationView>(),
        regenerateRequested: type<number>(),
        stopRequested: type<void>(),

        /** One server-sent event of the live turn, as the wire delivered it. */
        streamed: type<ChatEvent>(),
        /** The stream closed; a turn without `done` or `error` by then was cut. */
        streamEnded: type<void>(),
        /** The stream could not start or broke off; a catalog key or the server's sentence. */
        streamFailed: type<string>(),
    },
});
