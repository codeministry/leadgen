/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.packaging.PackageArchive;
import de.codeministry.leadgen.packaging.PackageArchiveService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The finished application package, as one file.
 *
 * <p>Separate from {@code OfferController} so the read model stays about the read model:
 * this one answers with bytes off the disk rather than with a view of a row.
 *
 * <p>Downloading is not sending. There is no recipient, no channel and no address here —
 * the operator gets the folder they would otherwise open in a file manager, and what
 * happens to it afterwards is still their decision.
 */
@RestController
@RequestMapping("/api/offers")
class PackageController {

    private final PackageArchiveService packages;

    PackageController(PackageArchiveService packages) {
        this.packages = packages;
    }

    @GetMapping("/{id}/package")
    ResponseEntity<StreamingResponseBody> download(@PathVariable long id) {
        Path folder = packages.folderFor(id).orElseThrow(() -> new NoPackage(id));
        // The folder name is already filesystem-safe: `PackagingService.safe` folds it to
        // [a-z0-9-] before it is ever written.
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(folder.getFileName() + ".zip", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(out -> PackageArchive.writeZip(folder, out));
    }

    /**
     * 404 with the reason in the body, the same convention the manual upload's 400 follows.
     * "No package has been built for offer 42" is actionable; a bare 404 reads as a broken
     * link to a page that is working exactly as designed.
     */
    @ExceptionHandler({NoPackage.class, PackageArchive.Rejected.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String noPackage(RuntimeException e) {
        return e.getMessage();
    }

    static class NoPackage extends RuntimeException {
        NoPackage(long id) {
            super("no package has been built for offer " + id);
        }
    }
}
