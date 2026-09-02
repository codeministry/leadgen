package de.codeministry.leadgen.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import de.codeministry.leadgen.archive.ArchiveService;
import de.codeministry.leadgen.offer.OfferFlags;
import de.codeministry.leadgen.offer.OfferQueryService;
import de.codeministry.leadgen.offer.OfferScoreView;
import de.codeministry.leadgen.offer.OfferView;
import de.codeministry.leadgen.offer.ShortlistEntry;
import de.codeministry.leadgen.score.ScoringService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The archive endpoint: the one thing about an offer a person owns. */
@WebMvcTest(OfferController.class)
class OfferControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OfferQueryService offers;

    @MockitoBean
    private ScoringService scoring;

    @MockitoBean
    private ArchiveService archive;

    @Test
    void answersWithTheWholeEntryRatherThanWithNothing() {
        // The browser replaces its row with what the server stored. A 204 would leave it
        // patching its own copy, which then disagrees until the next reload.
        given(archive.setArchived(anyLong(), anyBoolean())).willReturn(true);
        given(offers.find(1L)).willReturn(Optional.of(entry(Instant.parse("2026-09-02T08:00:00Z"), "MANUAL")));

        assertThat(mvc.patch()
                        .uri("/api/offers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":true}"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.offer.archiveSource")
                .isEqualTo("MANUAL");
    }

    @Test
    void restoresWithTheSameEndpointAndTheOtherValue() {
        given(archive.setArchived(anyLong(), anyBoolean())).willReturn(true);
        given(offers.find(1L)).willReturn(Optional.of(entry(null, "RESTORED")));

        assertThat(mvc.patch()
                        .uri("/api/offers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":false}"))
                .hasStatusOk();

        then(archive).should().setArchived(1L, false);
    }

    @Test
    void answers404ForAnOfferThatIsNotThere() {
        given(archive.setArchived(anyLong(), anyBoolean())).willReturn(false);

        assertThat(mvc.patch()
                        .uri("/api/offers/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":true}"))
                .hasStatus(404);
    }

    @Test
    void refusesAPatchThatSaysNothing() {
        // `Boolean` rather than `boolean`, so an absent field is a validation failure
        // naming the field instead of a Jackson error naming the type.
        assertThat(mvc.patch()
                        .uri("/api/offers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .hasStatus4xxClientError();
    }

    private static ShortlistEntry entry(Instant archivedAt, String source) {
        var offer = new OfferView(
                1L, "x", "Senior Java Entwickler", "Beschreibung", "https://example.invalid/1",
                "Köln", "freelancermap", null, null, List.of(), null, null, null, null, null,
                "de", null, null, archivedAt, source);
        return new ShortlistEntry(
                offer,
                new OfferScoreView(88, true, List.of(), null, null),
                new OfferFlags(false, true),
                List.of());
    }
}
