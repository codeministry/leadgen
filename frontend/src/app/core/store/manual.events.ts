import { type } from '@ngrx/signals';
import { eventGroup } from '@ngrx/signals/events';
import { ManualOfferFields, PendingDocument } from '@core/model/manual-document';

export const manualEvents = eventGroup({
  source: 'Manual',
  events: {
    opened: type<void>(),
    loaded: type<readonly PendingDocument[]>(),
    failed: type<string>(),
    uploaded: type<File>(),
    stored: type<PendingDocument>(),
    /** The corrected fields, written back into the file and moved where the source reads. */
    confirmed: type<{ name: string; fields: ManualOfferFields }>(),
    rejected: type<string>(),
    /** One document left the queue, whichever way it went. */
    settled: type<string>(),
  },
});
