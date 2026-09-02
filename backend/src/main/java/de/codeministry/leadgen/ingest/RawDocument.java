/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest;

import java.time.Instant;

/**
 * One fetched document, before anything is known about its contents. A newsletter
 * mail, a portal page, a dropped file — the connector's job ends here.
 *
 * @param id stable within the source: a file name now, an IMAP UID next
 */
public record RawDocument(String id, String subject, String html, Instant receivedAt) {}
