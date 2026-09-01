// FIXTURE — replace with the real endpoint (order of work, steps 5-9).
// `local-eml` is the one real source in the repo today; its numbers are the
// measured ones from the 14-mail corpus.
import { SourceSummary } from '@core/model/source-summary';

export const SOURCES_FIXTURE: readonly SourceSummary[] = [
  { id: 'local-eml', kind: 'file', enabled: true, layer: 'default', lastRunAt: '2026-09-01T06:00:00Z', documents: 14, extracted: 1289, announced: 1289, survived: 213 },
  { id: 'newsletter-imap', kind: 'imap', enabled: true, layer: 'config-dir', lastRunAt: '2026-09-01T06:00:00Z', documents: 2, extracted: 184, announced: 184, survived: 31 },
  { id: 'portal-rss', kind: 'rss', enabled: true, layer: 'config-dir', lastRunAt: '2026-09-01T06:00:00Z', documents: 1, extracted: 47, announced: null, survived: 9 },
  { id: 'archive-eml', kind: 'file', enabled: false, layer: 'config-dir', lastRunAt: null, documents: 0, extracted: 0, announced: null, survived: 0 },
];
