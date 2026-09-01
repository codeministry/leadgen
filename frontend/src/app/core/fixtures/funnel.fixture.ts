// FIXTURE — replace with the real endpoint (order of work, steps 5-9).
// These seven counts are measured, not invented: docs/SAMPLE-ANALYSIS.md over the
// 1289-offer corpus, reproduced exactly by the Java filter in HardFilterCorpusTest.
// 717 + 171 + 115 + 25 + 12 + 8 + 2 = 1050 removed, 239 left, 18.5 %.
// If the real endpoint ever disagrees with this shape, the endpoint is wrong.
import { FunnelStage } from '@shared/funnel-rail/funnel-stage';

export const FILTER_TOTAL_FIXTURE = 1289;

export const FILTER_STAGES_FIXTURE: readonly FunnelStage[] = [
  { id: 'abroad', label: 'Abroad', removed: 25 },
  { id: 'remote-share', label: 'Remote share below 80 %', removed: 12 },
  { id: 'distance', label: 'Beyond reach, not remote', removed: 717 },
  { id: 'stack-role', label: 'Foreign stack or wrong role', removed: 115 },
  { id: 'core-skill', label: 'No core skill', removed: 171 },
  { id: 'contract', label: 'Contract form rejected', removed: 8 },
  { id: 'stale', label: 'Older than 21 days', removed: 2 },
];
