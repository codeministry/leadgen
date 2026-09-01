// FIXTURE — replace with the real endpoint (order of work, steps 5-9).
// Values mirror backend/src/main/resources/leadgen/matching-rules.yaml.
import { RulesView } from '@core/model/rules-view';

export const RULES_FIXTURE: RulesView = {
  version: '1',
  weights: [
    { key: 'core_skill_overlap', points: 45 },
    { key: 'role_fit', points: 15 },
    { key: 'industry_fit', points: 10 },
    { key: 'seniority_fit', points: 10 },
    { key: 'project_setup', points: 10 },
    { key: 'rate_fit', points: 10 },
  ],
  penalties: [
    { key: 'stack_mismatch_dominant', points: -30 },
    { key: 'role_mismatch', points: -25 },
    { key: 'vague_description', points: -10 },
  ],
  thresholds: { autoShortlist: 70, review: 50, discard: 0 },
  knockouts: [
    { key: 'remote.min_remote_percent', label: 'Minimum remote share', value: '80 %' },
    { key: 'remote.accept_unknown', label: 'Keep offers with no stated remote share', value: 'yes' },
    { key: 'location.onsite_max_km', label: 'Maximum distance from home base', value: '120 km' },
    { key: 'location.country_allowlist', label: 'Countries', value: 'DE' },
    { key: 'rate.min_hourly_eur', label: 'Minimum hourly rate', value: '80 €' },
    { key: 'rate.apply_after', label: 'Rate rule runs after', value: 'enrichment' },
    { key: 'freshness.max_age_days', label: 'Maximum age', value: '14 days' },
    { key: 'language.preferred', label: 'Preferred language', value: 'de' },
  ],
  antiSkills: ['SAP ABAP', 'Salesforce Apex', 'Mainframe COBOL', 'Sharepoint'],
};
