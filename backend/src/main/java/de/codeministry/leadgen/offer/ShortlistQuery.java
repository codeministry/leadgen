package de.codeministry.leadgen.offer;

/**
 * What the shortlist screen is asking for.
 *
 * <p>A record rather than six parameters on a method, because the same six travel from
 * the controller to the query and back out as the next cursor.
 *
 * @param q free text over title, description and tags. Null or blank means no search.
 * @param band `shortlist`, `review`, or anything else for all of them. The two boundaries
 *     are the configured thresholds and are not stated here: naming them in a request would
 *     be the browser deciding what a band is, which is what moved this to the server.
 * @param portal a portal the offer or one of its duplicates was advertised on.
 * @param archived which side of the archive to show. False is the working list, which is
 *     what every other screen means by "the shortlist"; true is what has been taken off it,
 *     by age or by hand. Not a band: a band is a range of scores, and this decides which set
 *     the bands are applied to.
 * @param cursor the last row of the previous page, or null for the first.
 * @param limit how many rows to return.
 */
public record ShortlistQuery(
        String q, String band, String portal, boolean archived, String cursor, int limit) {

    /** Fifty is a screenful and a bit, which is what the list loads as you scroll. */
    public static final int DEFAULT_LIMIT = 50;

    /** A ceiling, so a hand-written request cannot ask for the whole archive again. */
    public static final int MAX_LIMIT = 200;

    public ShortlistQuery {
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }
}
