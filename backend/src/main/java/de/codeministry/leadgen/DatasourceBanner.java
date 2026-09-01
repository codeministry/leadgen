package de.codeministry.leadgen;

import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Prints which database the process actually reached.
 *
 * <p>The failure this prevents is a specific one: a developer machine usually already has a
 * Postgres on 5432, and connecting to the wrong one fails as
 * {@code password authentication failed for user "leadgen"} — a message that names the user
 * and nothing else. Host, port and database are exactly the three facts the message omits
 * and the only three that would have identified it, so they get said out loud, once.
 *
 * <p>Same convention as the frontend dev server printing its proxy target: whatever a
 * process talks to that is configurable, it names on startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatasourceBanner {

    private final DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            log.info("Database: {} as {}", metadata.getURL(), metadata.getUserName());
        } catch (SQLException e) {
            log.warn("Cannot describe the datasource", e);
        }
    }
}
