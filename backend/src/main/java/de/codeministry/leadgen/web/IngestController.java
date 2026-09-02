package de.codeministry.leadgen.web;

import de.codeministry.leadgen.ingest.IngestReport;
import de.codeministry.leadgen.ingest.IngestService;
import de.codeministry.leadgen.score.Judges;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs one ingest pass on demand. The scheduled run comes with the IMAP connector; until
 * then this is how a pass is triggered, and it stays useful afterwards for replaying a
 * file drop without waiting for a schedule.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class IngestController {

    private final IngestService ingest;

    /**
     * @param model which judge scores this pass, or absent for the configured default. The
     *     choice belongs to the run and not to the server: it is made in the select beside
     *     the button, travels with the request, and nothing about it is remembered
     *     afterwards. What it costs is in {@code ScoringService.run}.
     */
    @PostMapping("/ingest")
    IngestReport run(@RequestParam(required = false) String model) {
        return ingest.run(model);
    }

    /**
     * 400 rather than 404: the request reached the right endpoint and named a model nobody
     * configured. The sentence lists the ones that exist, because the usual cause is a
     * choice the browser kept in localStorage after it was removed from `.env`.
     */
    @ExceptionHandler(Judges.UnknownModel.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String unknownModel(Judges.UnknownModel e) {
        return e.getMessage();
    }
}
