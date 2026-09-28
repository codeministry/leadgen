import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';
import {ChatContextItem, ChatEvent, ChatStatus, ConversationSummary, ConversationView} from '@core/model/chat';

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
        /**
         * `?chat` as the URL now holds it — an id, `new`, `list`, or `null` when absent — and
         * `?chatCtx` beside it, the pins the conversation is asked under (ISC-446).
         */
        routed: type<{chat: string | null; ctx: string | null}>(),

        openRequested: type<number>(),
        /** A fresh conversation, optionally with the offer the drawer was opened from pinned. */
        newRequested: type<{pinnedOfferId: number | null}>(),
        /**
         * A screen's "Use this view" or "Ask about this offer" (ISC-451): into the open conversation
         * when there is one — stored, or `new` still gathering pins — and into a new one otherwise.
         * Several at once for "Ask about N offers" (ISC-453): one replacement, not one per offer, and
         * all of them refused together when the offers would pass the limit.
         */
        contextPinned: type<ChatContextItem | readonly ChatContextItem[]>(),
        /** A chip's ✕: out of the URL, and out of the stored conversation when there is one. */
        contextRemoved: type<ChatContextItem>(),
        /** The server's answer to `PUT …/context`: the context it now stores. */
        contextStored: type<ConversationView>(),
        contextFailed: type<string>(),
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

        /** Several conversations at once, named by id (ISC-478); the answer names what went. */
        bulkDeleteRequested: type<readonly number[]>(),
        bulkDeleted: type<readonly number[]>(),
        bulkDeleteFailed: type<string>(),
        /** After the last batch landed; not after a failed one. */
        bulkDeleteDone: type<void>(),

        /** The chat's own state for the empty chat's ring (ISC-476), asked each time its popover opens. */
        statusRequested: type<void>(),
        statusLoaded: type<ChatStatus>(),
        statusFailed: type<void>(),

        /** A new title; an empty one clears the name back to the derived one (ISC-449). */
        renameRequested: type<{id: number; title: string}>(),
        renamed: type<ConversationView>(),
        renameFailed: type<string>(),

        /** What the search field holds now (ISC-450); the read waits for the typing to pause. */
        searchChanged: type<string>(),
        /** The words a list read is actually sent with, so an empty result names what was asked. */
        listQueried: type<string>(),

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
