package de.codeministry.leadgen.web;

import de.codeministry.leadgen.manual.ManualDocumentName;
import de.codeministry.leadgen.manual.ManualOfferFields;
import de.codeministry.leadgen.manual.ManualUploadService;
import de.codeministry.leadgen.manual.PendingDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The first endpoint in this application that puts a file on disk.
 *
 * <p>An upload lands in `pending/`, which no source reads, and becomes an offer only when
 * somebody confirms it. That order is the point: a pasted ad can be extracted wrongly, and
 * the shortlist is the one list that gets trusted instead of the mailbox.
 *
 * <p>It writes documents and nothing else. There is no recipient here, no channel and no
 * address — the same invariant the packaging stage is guarded by.
 */
@RestController
@RequestMapping("/api/sources/manual")
class ManualSourceController {

    private final ManualUploadService uploads;

    ManualSourceController(ManualUploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    PendingDocument upload(@RequestParam("file") MultipartFile file) {
        try {
            return uploads.store(file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the upload", e);
        }
    }

    @GetMapping("/pending")
    List<PendingDocument> pending() {
        return uploads.pending();
    }

    @GetMapping("/pending/{name}")
    PendingDocument one(@PathVariable String name) {
        return uploads.find(name).orElseThrow(() -> new NotFound(name));
    }

    /** Writes the corrected fields into the file and moves it where the source reads. */
    @PostMapping("/pending/{name}/confirm")
    PendingDocument confirm(@PathVariable String name, @RequestBody ManualOfferFields fields) {
        return uploads.confirm(name, fields);
    }

    @DeleteMapping("/pending/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reject(@PathVariable String name) {
        if (!uploads.reject(name)) {
            throw new NotFound(name);
        }
    }

    /**
     * A refused name, extension or size is a 400 with the reason in the body. The reason
     * matters: "only .md documents are accepted" is actionable, a bare 400 is not.
     */
    @ExceptionHandler(ManualDocumentName.Rejected.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String rejected(ManualDocumentName.Rejected e) {
        return e.getMessage();
    }

    /** Not configured is not the client's mistake, and not a 500 either. */
    @ExceptionHandler(ManualUploadService.NoInbox.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    String noInbox(ManualUploadService.NoInbox e) {
        return e.getMessage();
    }

    @ExceptionHandler(NotFound.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(NotFound e) {
        return e.getMessage();
    }

    static class NotFound extends RuntimeException {
        NotFound(String name) {
            super("no document named '" + name + "' is waiting for review");
        }
    }
}
