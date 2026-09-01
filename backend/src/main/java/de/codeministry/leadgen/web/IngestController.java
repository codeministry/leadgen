package de.codeministry.leadgen.web;

import de.codeministry.leadgen.ingest.IngestReport;
import de.codeministry.leadgen.ingest.IngestService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs one ingest pass on demand. The scheduled run comes with the IMAP connector; until
 * then this is how a pass is triggered, and it stays useful afterwards for replaying a
 * file drop without waiting for a schedule.
 */
@RestController
@RequestMapping("/api")
public class IngestController {

    private final IngestService ingest;

    IngestController(IngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping("/ingest")
    IngestReport run() {
        return ingest.run();
    }
}
