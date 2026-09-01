package de.codeministry.leadgen.enrich;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remembers what a URL answered, so a second run inside the TTL asks nobody.
 *
 * <p>In Postgres rather than on disk: the TTL is a week, the container has no volume for
 * a scratch directory, and a cache that does not survive a restart turns a rate limit
 * into a promise nobody keeps.
 *
 * <p>Failures are cached too, and deliberately: a 403 or a path disallowed by robots.txt
 * is a fact about the page, not about the moment. A timeout is the exception — that one
 * the fetcher does not store, because remembering one bad minute for a week is worse than
 * asking again tomorrow.
 */
@Component
public class PageCache {

    private final JdbcClient jdbc;

    PageCache(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public Optional<Entry> find(String url, Duration ttl) {
        return jdbc.sql("SELECT status, body, fetched_at FROM fetched_page WHERE url = ? AND fetched_at >= ?")
                .params(url, java.sql.Timestamp.from(Instant.now().minus(ttl)))
                .query((rs, row) -> new Entry(rs.getInt("status"), rs.getString("body")))
                .optional();
    }

    @Transactional
    public void store(String url, int status, String body) {
        jdbc.sql(
                        """
                        INSERT INTO fetched_page (url, status, body, fetched_at)
                        VALUES (?, ?, ?, now())
                        ON CONFLICT (url) DO UPDATE
                        SET status = EXCLUDED.status, body = EXCLUDED.body, fetched_at = now()
                        """)
                .params(url, status, body)
                .update();
    }

    /** @param body null when the page could not be read; the status says why. */
    public record Entry(int status, String body) {}
}
