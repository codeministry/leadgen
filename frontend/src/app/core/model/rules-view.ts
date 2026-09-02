/**
 * The rule set as the screen shows it. `weights` and `penalties` are open maps
 * in matching-rules.yaml, so nothing here may hardcode their keys.
 */
export interface RuleWeight {
  readonly key: string;
  readonly points: number;
}

/**
 * Exactly one of the two carries the rule: a scalar states `value` and leaves `values`
 * empty, a list states `values` and leaves `value` null. An empty list is a scalar again,
 * because the sentence it gets instead ("nothing", "everywhere") is what is shown.
 */
export interface KnockoutRule {
  readonly key: string;
  readonly label: string;
  readonly value: string | null;
  readonly values: readonly string[];
}

export interface RulesView {
  readonly version: string;
  readonly weights: readonly RuleWeight[];
  readonly penalties: readonly RuleWeight[];
  readonly thresholds: {
    readonly autoShortlist: number;
    readonly review: number;
    readonly discard: number;
  };
  /**
   * How old an advert may be before it leaves the working list, or null when nothing
   * archives by age. Beside the knockouts and not among them: it no longer rejects
   * anything, it only decides what is on today's list.
   */
  readonly archiveAfterDays: number | null;
  readonly knockouts: readonly KnockoutRule[];
  readonly antiSkills: readonly string[];
}
