package de.codeministry.leadgen.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.ConfigFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The wired pass: config, connector, extraction and the write into Postgres. */
@SpringBootTest
@Testcontainers
class IngestServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String MANUAL_OFFER =
            """
            ---
            title: Senior Java Entwickler, gefunden auf LinkedIn
            url: https://portal.example/p/98765
            location: Köln
            agency: Beispiel GmbH
            published: 2026-09-01
            tags: [Java, Spring Boot]
            ---
            Ablösung eines Monolithen.
            """;

    @Autowired
    private IngestService ingest;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> enabledLocalEmlConfig().toString());
    }

    @Test
    void extractsAndStoresEveryOfferOfTheFixture() {
        var report = ingest.run();

        // Filtered by id rather than asserted as the only result: `manual-inbox` is
        // enabled in the shipped defaults, and it reports an empty directory here.
        assertThat(report.sources())
                .filteredOn(source -> source.sourceId().equals("local-eml"))
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.documents()).isEqualTo(1);
                    assertThat(source.extracted()).isEqualTo(3);
                    assertThat(source.written()).isEqualTo(3);
                });
        assertThat(rows()).isEqualTo(3);
    }

    @Test
    void readsAnOfferSomebodyDroppedInTheInboxByHand() {
        // The whole point of the manual source: no new connector, no second code path, and
        // it needs no upload at all — a file in the directory is enough.
        var report = ingest.run();

        assertThat(report.sources())
                .filteredOn(source -> source.sourceId().equals("manual-inbox"))
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.documents()).isEqualTo(1);
                    assertThat(source.extracted()).isEqualTo(1);
                    assertThat(source.written()).isEqualTo(1);
                });
        assertThat(jdbc.queryForObject(
                        "SELECT title FROM offer o JOIN source s ON s.id = o.source_id WHERE s.name = 'manual-inbox'",
                        String.class))
                .isEqualTo("Senior Java Entwickler, gefunden auf LinkedIn");
    }

    @Test
    void readingTheSameMailAgainAddsNothing() {
        // A newsletter arrives daily and repeats what is still open, so re-reading is the
        // normal case, not the exceptional one. Without the upsert every run would
        // multiply the archive.
        ingest.run();
        int afterFirst = rows();
        ingest.run();

        assertThat(rows()).isEqualTo(afterFirst);
    }

    @Test
    void storesTheTagsTheOfferWasGroupedUnder() {
        ingest.run();

        var tags = jdbc.queryForList(
                "SELECT array_to_string(tags, ',') FROM offer WHERE title LIKE 'Platform%'", String.class);
        assertThat(tags).containsExactly("Kubernetes");
    }

    /** Scoped to one source: `manual-inbox` ships enabled and contributes a row of its own. */
    private int rows() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM offer o JOIN source s ON s.id = o.source_id WHERE s.name = 'local-eml'",
                Integer.class);
    }

    /** The shipped example with `local-eml` enabled and pointed at the test fixture. */
    private static Path enabledLocalEmlConfig() {
        try {
            Path dir = Files.createTempDirectory("leadgen-ingest");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);

            Path fixture = Path.of("src/test/resources/ingest/mails").toAbsolutePath();
            Path sources = dir.resolve("sources.yaml");
            Files.writeString(
                    sources,
                    Files.readString(sources)
                            .replace("id: local-eml\n    enabled: false", "id: local-eml\n    enabled: true")
                            .replace("path: ${INBOX_DIR:./data/inbox}", "path: " + fixture));

            // No path to set: `manual-inbox` ships enabled and its path names a directory
            // inside the configuration directory, which is this one.
            Path inbox = Files.createDirectories(dir.resolve("inbox"));
            Files.writeString(inbox.resolve("found-by-hand.md"), MANUAL_OFFER);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
