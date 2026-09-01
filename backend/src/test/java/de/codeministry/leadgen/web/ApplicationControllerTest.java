package de.codeministry.leadgen.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import de.codeministry.leadgen.application.ApplicationService;
import de.codeministry.leadgen.application.ApplicationStatus;
import de.codeministry.leadgen.application.ApplicationView;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The first write endpoint: what it accepts, and what it refuses. */
@WebMvcTest(ApplicationController.class)
class ApplicationControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ApplicationService applications;

    @Test
    void recordsAStatusTheOperatorReports() {
        given(applications.update(anyLong(), any())).willReturn(view(ApplicationStatus.SENT));

        assertThat(mvc.patch()
                        .uri("/api/applications/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SENT\",\"sentOn\":\"2026-09-01\"}"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("SENT");
    }

    @Test
    void refusesAStatusThatIsNotOneOfTheEleven() {
        // A typo has to fail at the door rather than reaching the database as a string
        // nothing can read back.
        assertThat(mvc.patch()
                        .uri("/api/applications/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"POSTED\"}"))
                .hasStatus4xxClientError();
    }

    @Test
    void refusesAnUpdateWithNoStatusAtAll() {
        assertThat(mvc.patch()
                        .uri("/api/applications/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"just a note\"}"))
                .hasStatus4xxClientError();
    }

    @Test
    void answersNotFoundForAnIdThatNamesNothing() {
        willThrow(new ApplicationService.ApplicationNotFound(42))
                .given(applications)
                .update(anyLong(), any());

        assertThat(mvc.patch()
                        .uri("/api/applications/42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SENT\"}"))
                .hasStatus(org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test
    void servesTheLanesSoTheBoardDoesNotHardcodeThem() {
        assertThat(mvc.get().uri("/api/applications/lanes"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$[0].id")
                .isEqualTo("backlog");
    }

    private static ApplicationView view(ApplicationStatus status) {
        return new ApplicationView(
                1L, 2L, status, "Senior Java Entwickler (m/w/d)", "Etengo AG", "FreelancerMap",
                "https://example.invalid/x", 88, null, null, LocalDate.of(2026, 9, 1), null, false,
                null, null, Instant.now());
    }
}
