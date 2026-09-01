package de.codeministry.leadgen.filter;

/**
 * The stages, in the order they run. The order is not cosmetic: each one is cheaper and
 * more certain than the next, and the counts per stage only sum to the total because an
 * offer stops at the first one that rejects it.
 *
 * <p>The rate rule is deliberately absent. It is configured as `apply_after: enrichment`
 * and the config loader refuses any other value, because the sources state a rate in
 * 0.0 % of offers — applied here it would discard everything or nothing.
 */
public enum FilterStage {
    ABROAD("abroad"),
    REMOTE_SHARE("remote share below the minimum"),
    OUT_OF_REACH("beyond reach, not remote"),
    ROLE_OR_STACK("foreign stack or wrong role"),
    NO_CORE_SKILL("no core skill"),
    CONTRACT_FORM("contract form rejected"),
    STALE("older than the freshness limit");

    private final String description;

    FilterStage(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
