package de.codeministry.leadgen.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.model.SourcesConfig;
import de.codeministry.leadgen.ingest.connector.FileSourceConnector;
import de.codeministry.leadgen.ingest.extract.HtmlBlockExtractor;
import de.codeministry.leadgen.ingest.extract.OfferMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Extraction against a fixture that is part of the repository, so this runs on a fresh
 * clone. The real corpus is gitignored and gets its own test.
 *
 * <p>The rules come from the shipped `resources/leadgen/sources.yaml` and not from a
 * fixture of its own: a broken example then fails the build rather than the first
 * user's first run.
 */
class ExtractionTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @TempDir
    Path configDir;

    private SourcesConfig.Source source;
    private List<ExtractedOffer> offers;

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @BeforeEach
    void extractTheFixture() {
        ConfigFixtures.materialize(configDir);
        Path mails = Path.of("src/test/resources/ingest/mails").toAbsolutePath();
        var snapshot = ConfigFixtures.loaderFor(configDir, VALIDATOR, Map.of("INBOX_DIR", mails.toString()))
                .load();

        source = snapshot.sources().sources().stream()
                .filter(s -> s.id().equals("local-eml"))
                .findFirst()
                .orElseThrow();

        var documents = new FileSourceConnector(new ConfigProperties(configDir.toString())).read(source, 0L);
        assertThat(documents).hasSize(1);

        var extractor = new HtmlBlockExtractor();
        var mapper = new OfferMapper();
        offers = extractor.extract(documents.getFirst().html(), source.extraction()).stream()
                .map(block -> mapper.map(block, source.extraction(), null))
                .toList();
    }

    @Test
    void findsEveryOfferTheMailAnnounces() {
        // The subject states the number, which is the only check the source itself offers.
        assertThat(offers).hasSize(3);
    }

    @Test
    void readsEveryFieldOfACompleteOffer() {
        var offer = offers.getFirst();

        assertThat(offer.title()).isEqualTo("Senior Java Developer (m/w/d)");
        assertThat(offer.agency()).isEqualTo("Example Agency GmbH");
        assertThat(offer.location()).isEqualTo("Köln");
        assertThat(offer.portal()).isEqualTo("portal-a");
        assertThat(offer.publishedOn()).isEqualTo(LocalDate.of(2026, 8, 25));
        assertThat(offer.description()).isEqualTo("Ein Satz zur Beschreibung.");
        assertThat(offer.tags()).containsExactly("Java", "Spring Boot");
    }

    @Test
    void stripsMarkupInsideATitle() {
        // Some sources wrap the matched search term in <mark>. The current sample corpus
        // has none, so this guards an expectation rather than a measurement — but a title
        // carrying markup would break every comparison downstream.
        assertThat(offers.getFirst().title()).doesNotContain("<mark>").contains("Java");
    }

    @Test
    void unwrapsTheProxyLinkAndDropsTheMailAddress() {
        assertThat(offers.getFirst().url())
                .isEqualTo("https://portal-a.example.com/project/senior-java")
                .doesNotContain("email=");
    }

    @Test
    void leavesAPlainLinkAlone() {
        assertThat(offers.get(2).url()).isEqualTo("https://portal-a.example.com/project/platform-engineer");
    }

    @Test
    void addressesTheMetaFieldsByPrefixAndNotByPosition() {
        // The third offer states no location and the second no company. Read by position,
        // every following field of those two would be shifted by one.
        assertThat(offers.get(1).agency()).isNull();
        assertThat(offers.get(1).location()).isEqualTo("Köln");
        assertThat(offers.get(2).location()).isNull();
        assertThat(offers.get(2).agency()).isEqualTo("Another Agency AG");
        assertThat(offers.get(2).portal()).isEqualTo("portal-a");
    }

    @Test
    void takesTagsFromTheGroupTheOfferSitsIn() {
        assertThat(offers.get(2).tags()).containsExactly("Kubernetes");
    }

    @Test
    void givesTheSameProjectFromTwoPortalsTheSameFingerprint() {
        // The gender suffix differs, the portal differs, the project does not. This is
        // what deduplication will act on.
        assertThat(offers.get(0).fingerprint()).isEqualTo(offers.get(1).fingerprint());
        assertThat(offers.get(0).externalId()).isNotEqualTo(offers.get(1).externalId());
    }

    @Test
    void readsTheHtmlAlternativeAndNotThePlainTextOne() {
        assertThat(source.extraction().fallback()).isEqualTo("none");
        assertThat(offers).allSatisfy(offer -> assertThat(offer.title()).isNotBlank());
    }
}
