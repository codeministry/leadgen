package de.codeministry.leadgen.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.ingest.connector.FileSourceConnector;
import de.codeministry.leadgen.ingest.extract.HtmlBlockExtractor;
import de.codeministry.leadgen.ingest.extract.OfferMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The acceptance test for step 3: the Java extraction has to reproduce what
 * `docs/samples/analyze_samples.py` measured. The numbers below are that measurement
 * (`docs/SAMPLE-ANALYSIS.md`), not a snapshot of this implementation — a deviation is a
 * bug, not a value to update.
 *
 * <p><b>Skipped without the corpus.</b> The mails are gitignored: they carry the
 * subscriber's address in their headers and unsubscribe links, so they cannot be part of
 * a public repository. That makes this test unavailable on a fresh clone and in CI, which
 * is why {@link ExtractionTest} covers the same mechanics against a fixture that ships.
 */
class SampleCorpusAcceptanceTest {

    private static final Pattern ANNOUNCED = Pattern.compile("^(\\d+)");

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @TempDir
    static Path configDir;

    private static List<RawDocument> documents;
    private static List<ExtractedOffer> offers;
    private static List<Integer> perDocument;

    @BeforeAll
    static void extractTheCorpus() {
        Path corpus = ConfigFixtures.repositoryRoot().resolve("docs/samples/emails");
        Assumptions.assumeTrue(
                Files.isDirectory(corpus),
                "docs/samples/emails is absent — the corpus is gitignored, see docs/SAMPLE-ANALYSIS.md");

        ConfigFixtures.materialize(configDir);
        var snapshot = ConfigFixtures.loaderFor(configDir, VALIDATOR, Map.of("INBOX_DIR", corpus.toString()))
                .load();
        var source = snapshot.sources().sources().stream()
                .filter(s -> s.id().equals("local-eml"))
                .findFirst()
                .orElseThrow();

        var extractor = new HtmlBlockExtractor();
        var mapper = new OfferMapper();
        documents = new FileSourceConnector(new ConfigProperties(configDir.toString())).read(source, 0L);
        offers = new ArrayList<>();
        perDocument = new ArrayList<>();

        for (RawDocument document : documents) {
            var extracted = extractor.extract(document.html(), source.extraction()).stream()
                    .map(block -> mapper.map(block, source.extraction()))
                    .toList();
            perDocument.add(extracted.size());
            offers.addAll(extracted);
        }
    }

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void extractsTheMeasuredNumberOfOffers() {
        assertThat(documents).hasSize(14);
        assertThat(offers).hasSize(1289);
    }

    @Test
    void matchesTheCountEachMailAnnouncesInItsSubject() {
        // The only check the source itself offers, and it holds for all 14. That is what
        // makes `fallback: none` defensible: nothing is quietly lost.
        for (int i = 0; i < documents.size(); i++) {
            Matcher matcher = ANNOUNCED.matcher(documents.get(i).subject());
            assertThat(matcher.find()).as("subject of %s starts with a count", documents.get(i).id()).isTrue();
            assertThat(perDocument.get(i))
                    .as("offers extracted from %s", documents.get(i).id())
                    .isEqualTo(Integer.parseInt(matcher.group(1)));
        }
    }

    @Test
    void reproducesTheMeasuredFieldCoverage() {
        assertThat(count(o -> o.title() != null)).as("title").isEqualTo(1289);
        assertThat(count(o -> o.url() != null)).as("url").isEqualTo(1289);
        assertThat(count(o -> o.portal() != null)).as("portal").isEqualTo(1289);
        assertThat(count(o -> o.publishedOn() != null)).as("published").isEqualTo(1289);
        assertThat(count(o -> !o.tags().isEmpty())).as("tags").isEqualTo(1289);
        assertThat(count(o -> o.description() != null)).as("description").isEqualTo(1288);
        assertThat(count(o -> o.location() != null)).as("location").isEqualTo(1283);
        assertThat(count(o -> o.agency() != null)).as("agency").isEqualTo(1170);
    }

    @Test
    void neverLetsTheSubscriberAddressThroughTheProxyLink() {
        // The invariant with the sharpest consequence: every link in the corpus is a
        // tracking proxy carrying the address, and anything derived from an unwrapped
        // link would carry it into the database and into every exported package.
        assertThat(offers).allSatisfy(offer -> assertThat(offer.url())
                .doesNotContain("email=")
                .doesNotContain("@")
                .doesNotContain("%40"));
    }

    @Test
    void findsTheMeasuredNumberOfDuplicateTitles() {
        // 159 of 1289, 12.3 %, by normalized title alone. This is what makes
        // deduplication a step-5 concern rather than a later one.
        long distinct = offers.stream().map(ExtractedOffer::fingerprint).distinct().count();
        assertThat(offers.size() - distinct).isEqualTo(159);
    }

    @Test
    void statesAnHourlyRateInNoOfferAtAll() {
        // 0.0 %. The reason `min_hourly_eur` must not apply before the enrichment stage —
        // enforced at config load, measured here.
        Pattern rate = Pattern.compile("(\\d{2,4})\\s*(?:[,.]\\d{2})?\\s*(?:€|EUR|Euro)", Pattern.CASE_INSENSITIVE);
        assertThat(count(o -> o.description() != null && rate.matcher(o.description()).find()))
                .isZero();
    }

    private static int count(Predicate<ExtractedOffer> predicate) {
        return (int) offers.stream().filter(predicate).count();
    }
}
