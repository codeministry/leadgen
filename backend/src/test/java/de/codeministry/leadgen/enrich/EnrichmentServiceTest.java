package de.codeministry.leadgen.enrich;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.config.ConfigFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * The only stage that leaves the machine, against a stubbed portal.
 *
 * <p>Everything here is about what happens when the network does not cooperate, because
 * that is the part with consequences: an offer whose ad cannot be read must stay in the
 * pipeline, and a stage that asks a portal more often than it said it would is a stage
 * that gets blocked.
 */
@SpringBootTest
@Testcontainers
class EnrichmentServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final WireMockServer PORTAL =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private static final String AD_HTML =
            """
            <html><body>
              <h1>Senior Java Entwickler (m/w/d)</h1>
              <article>
                Für ein Logistikunternehmen suchen wir Verstärkung.
                Stundensatz 95 EUR/h, Laufzeit 12 Monate, 4 Tage / Woche,
                80 % remote, Start ab 01.10.2026.
                Ansprechpartnerin Frau Meier | Telefon 0221 1234567
              </article>
            </body></html>
            """;

    @Autowired
    private EnrichmentService enrichment;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @BeforeAll
    static void startPortal() {
        PORTAL.start();
        WireMock.configureFor("localhost", PORTAL.port());
    }

    @AfterAll
    static void stopPortal() {
        PORTAL.stop();
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> shippedDefaults().toString());
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM fetched_page");
        jdbc.update("DELETE FROM source");
        sourceId = jdbc.queryForObject(
                "INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        PORTAL.resetAll();
        allowEverything();
    }

    @Test
    void readsTheFieldsTheNewsletterNeverStated() {
        // ISC-45. The newsletter states a rate in 0.0 % of 1289 offers; without the
        // original ad the scoring stage would be judging a two-line summary.
        stubFor(get(urlPathEqualTo("/projekt/1")).willReturn(aResponse().withBody(AD_HTML)));
        long id = passedOffer("/projekt/1");

        var report = enrichment.run();

        assertThat(report.considered()).isEqualTo(1);
        assertThat(report.enriched()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT rate_eur FROM offer WHERE id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("95");
        assertThat(jdbc.queryForObject("SELECT duration FROM offer WHERE id = ?", String.class, id))
                .isEqualTo("12");
        assertThat(jdbc.queryForObject("SELECT remote_percent FROM offer WHERE id = ?", Integer.class, id))
                .isEqualTo(80);
        assertThat(jdbc.queryForObject("SELECT starts_on FROM offer WHERE id = ?", LocalDate.class, id))
                .isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(jdbc.queryForObject("SELECT full_text FROM offer WHERE id = ?", String.class, id))
                .contains("Logistikunternehmen");
        assertThat(jdbc.queryForObject("SELECT enrichment_note FROM offer WHERE id = ?", String.class, id))
                .isNull();
    }

    @Test
    void keepsAnOfferWhoseAdIsForbidden() {
        // ISC-46. A portal having a bad afternoon must not cost a good project.
        stubFor(get(urlPathEqualTo("/projekt/2")).willReturn(aResponse().withStatus(403)));
        long id = passedOffer("/projekt/2");

        var report = enrichment.run();

        assertThat(report.incomplete()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM offer WHERE id = ?", String.class, id))
                .isEqualTo("PASSED");
        assertThat(jdbc.queryForObject("SELECT enrichment_note FROM offer WHERE id = ?", String.class, id))
                .contains("403");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offer", Integer.class)).isEqualTo(1);
    }

    @Test
    void asksNobodyOnASecondRunInsideTheCacheTtl() {
        // ISC-47, first half. The cache is what turns a daily run into one request per
        // ad per week instead of one per ad per day.
        stubFor(get(urlPathEqualTo("/projekt/3")).willReturn(aResponse().withBody(AD_HTML)));
        passedOffer("/projekt/3");
        enrichment.run();

        jdbc.update("UPDATE offer SET enriched_at = NULL, enrichment_note = NULL");
        var second = enrichment.run();

        assertThat(second.fromCache()).isEqualTo(1);
        assertThat(second.requests()).isZero();
        verify(1, getRequestedFor(urlPathEqualTo("/projekt/3")));
    }

    @Test
    void doesNotFetchAPathRobotsTxtDisallows() {
        // ISC-47, second half. Not fetching is the point; the offer still survives.
        PORTAL.stubFor(get(urlEqualTo("/robots.txt"))
                .willReturn(aResponse().withBody("User-agent: *\nDisallow: /intern/\n")));
        stubFor(get(urlPathEqualTo("/intern/projekt")).willReturn(aResponse().withBody(AD_HTML)));
        long id = passedOffer("/intern/projekt");

        var report = enrichment.run();

        assertThat(report.incomplete()).isEqualTo(1);
        assertThat(report.requests()).isZero();
        verify(0, getRequestedFor(urlPathEqualTo("/intern/projekt")));
        assertThat(jdbc.queryForObject("SELECT enrichment_note FROM offer WHERE id = ?", String.class, id))
                .contains("robots.txt");
    }

    @Test
    void remembersADisallowedPathSoTheNextRunDoesNotAskAgain() {
        PORTAL.stubFor(get(urlEqualTo("/robots.txt"))
                .willReturn(aResponse().withBody("User-agent: *\nDisallow: /intern/\n")));
        passedOffer("/intern/projekt");
        enrichment.run();

        jdbc.update("UPDATE offer SET enriched_at = NULL, enrichment_note = NULL");
        var second = enrichment.run();

        assertThat(second.fromCache()).isEqualTo(1);
        assertThat(second.requests()).isZero();
    }

    @Test
    void enrichesOnlyWhatPassedTheFilter() {
        // Fetching a thousand ads to then discard eight hundred would be rude to the
        // portals and slow for nothing.
        stubFor(get(urlPathEqualTo("/projekt/4")).willReturn(aResponse().withBody(AD_HTML)));
        offer("/projekt/4", "FILTERED_OUT");

        assertThat(enrichment.run().considered()).isZero();
        verify(0, getRequestedFor(urlPathEqualTo("/projekt/4")));
    }

    private void allowEverything() {
        PORTAL.stubFor(get(urlEqualTo("/robots.txt")).willReturn(aResponse().withStatus(404)));
    }

    private long passedOffer(String path) {
        return offer(path, "PASSED");
    }

    private long offer(String path, String status) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, url, fingerprint, status)
                VALUES (?, ?, 'Senior Java Entwickler (m/w/d)', ?, 'senior java entwickler', ?)
                RETURNING id
                """,
                Long.class,
                sourceId,
                path,
                PORTAL.baseUrl() + path,
                status);
    }

    /** The shipped defaults: a broken default has to fail the build, not the first user. */
    private static Path shippedDefaults() {
        try {
            Path dir = Files.createTempDirectory("leadgen-enrich");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
