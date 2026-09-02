package de.codeministry.leadgen.analytics;

import java.time.LocalDate;
import java.util.List;

/** Where the offers come from, what they are filed under, and where the work sits. */
public record MarketView(
        List<Portal> portals, List<Tag> tags, List<Location> locations, Reach reach, List<StageDay> stageMix) {

    /**
     * @param listings what this portal published, duplicates included. The primaries-only
     *     rule is deliberately not applied to this one number: a portal that carried an ad
     *     did carry it, even when another portal got there first and holds the primary, and
     *     counting primaries only would show a portal with zero listings that publishes
     *     every day.
     * @param projects distinct projects it brought in. The survival rate is computed on
     *     this, because that is the set the funnel and the shortlist count.
     */
    public record Portal(String portal, int listings, int projects, int passed, int shortlisted) {}

    /**
     * A search tag, which is <b>not</b> a skill. `V2__offer_tags.sql` calls them "the search
     * tags the aggregator groups its offers by" — they are the source's own filing
     * categories, not technologies read out of the advert. Charting them as demand for a
     * named technology would be a claim the data does not support.
     */
    public record Tag(String tag, int projects, int passed) {}

    /**
     * The location as the advert stated it, unnormalised. Deduplication already established
     * that "Remote und Nürnberg" and "Nürnberg" are the same place and that a location has
     * to be parsed before it can be compared; `TextFold` is the one place normalisation
     * lives, and half of it inside a chart query is worse than none.
     */
    public record Location(String location, int projects, int passed) {}

    /**
     * The location question that is decided rather than guessed: what the filter did about
     * reach. Three counts, no free-text comparison anywhere.
     */
    public record Reach(int outOfReach, int abroad, int remoteShare) {}

    /**
     * One day, one stage, and what it removed.
     *
     * <p>Days rather than weeks so the browser buckets this the same way it buckets the
     * intake series. Aggregated twice, in two places, the two charts drift apart the first
     * time either rule changes — and they did: a weekly stage mix showed one bar while the
     * intake above it showed two days.
     */
    public record StageDay(LocalDate day, String stage, int removed) {}
}
