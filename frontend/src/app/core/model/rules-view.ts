/**
 * The rule set as the screen shows it. `weights` and `penalties` are open maps
 * in matching-rules.yaml, so nothing here may hardcode their keys.
 */
export interface RuleWeight {
  readonly key: string;
  readonly points: number;
}

export interface KnockoutRule {
  readonly key: string;
  readonly label: string;
  readonly value: string;
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
  readonly knockouts: readonly KnockoutRule[];
  readonly antiSkills: readonly string[];
}
