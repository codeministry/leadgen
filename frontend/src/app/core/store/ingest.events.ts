import { type } from '@ngrx/signals';
import { eventGroup } from '@ngrx/signals/events';
import { IngestReport } from '@core/api/ingest.api';

export const ingestEvents = eventGroup({
  source: 'Ingest',
  events: {
    requested: type<void>(),
    finished: type<IngestReport>(),
    failed: type<string>(),
  },
});
