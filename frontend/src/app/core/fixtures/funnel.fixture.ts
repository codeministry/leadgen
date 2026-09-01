// FIXTURE — replace with the real endpoint (order of work, steps 5-9).
// These five counts are measured, not invented: docs/SAMPLE-ANALYSIS.md over the
// 1289-offer corpus. 152 + 11 + 666 + 97 + 150 = 1076 removed, 213 left, 16.5 %.
// If the real endpoint ever disagrees with this shape, the endpoint is wrong.
import { FunnelStage } from '@shared/funnel-rail/funnel-stage';

export const FILTER_TOTAL_FIXTURE = 1289;

export const FILTER_STAGES_FIXTURE: readonly FunnelStage[] = [
  { id: 'abroad', label: 'Abroad', removed: 152 },
  { id: 'remote-share', label: 'Remote share below 80 %', removed: 11 },
  { id: 'distance', label: 'Beyond 120 km, not remote', removed: 666 },
  { id: 'stack-role', label: 'Foreign stack or wrong role', removed: 97 },
  { id: 'core-skill', label: 'No core skill', removed: 150 },
];
