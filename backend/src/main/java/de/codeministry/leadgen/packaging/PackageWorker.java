/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import de.codeministry.leadgen.application.PackageRequested;
import de.codeministry.leadgen.archive.OffersArchived;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The two things that touch the disk in reaction to a decision somebody made.
 *
 * <p><b>Both run after commit, and that is the whole design.</b> A folder written inside the
 * transaction that requested it survives a rollback the row does not, and a folder deleted
 * inside one disappears even when the archive write is undone. Neither failure has anything
 * in the diff to show for it, and only one of the two is recoverable. Committing first means
 * the database is always the authority and the disk catches up.
 *
 * <p><b>And both are safe to lose.</b> The build leaves the offer due — {@code PackagingService.DUE}
 * asks for a PACKAGED application with no {@code packaged_at} — so the next run's PACKAGE
 * stage is the retry, and the operator sees "building" rather than a wrong folder in the
 * meantime. The discard leaves a folder standing, which {@code OrphanSweep} collects. That is
 * what makes asynchronous acceptable here: nothing is lost by a listener that never ran, only
 * delayed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PackageWorker {

    private final PackagingService packaging;
    private final PackageArchiveService packages;

    /**
     * Builds the folder for an application that has just reached PACKAGED.
     *
     * <p>The event carries the offer and nothing else: what may be built is decided by the
     * query, against the state as it stands now, which is a second chance for the offer to
     * have moved on in between.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onPackageRequested(PackageRequested event) {
        try {
            PackageReport report = packaging.buildFor(event.offerId());
            if (report.built() == 0 && report.failed() == 0) {
                log.debug("Offer {} asked for a package and needed none", event.offerId());
            }
        } catch (RuntimeException e) {
            // `buildFor` already catches per offer; this is the query or the configuration
            // failing, which the next run retries in exactly the same way.
            log.error("The package for offer {} could not be built: {}", event.offerId(), e.getMessage(), e);
        }
    }

    /**
     * Throws away the packages of offers a person has just archived, unless they were sent.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onOffersArchived(OffersArchived event) {
        try {
            packages.discard(event.offerIds());
        } catch (RuntimeException e) {
            log.error("The packages of {} archived offers could not be discarded: {}",
                event.offerIds().size(), e.getMessage(), e);
        }
    }
}
