/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * One document to zero or more offers, entirely by configuration.
 *
 * <p>No selector, no field name and no date format is written in Java. That is what makes a
 * new source a YAML block rather than a release, and the <b>eight field names</b> — title,
 * url, description, location, portal, agency, published, tags — are the contract with
 * {@code OfferMapper}. A field spelled differently is extracted and then ignored, in silence.
 *
 * <p>Only the prose field is converted to Markdown, and it is named rather than inferred: the
 * markup does not say which field is a document, and a title in an {@code <h3>} would
 * otherwise arrive as "### Senior Java Developer" in the shortlist, in the fingerprint and in
 * the cover letter. Everything else stays flat text. Consequently <b>a pattern reads a line
 * and a field reads a document</b>.
 *
 * <p>{@code ProxyLink} is a privacy boundary, not a convenience: every link in a newsletter
 * carries the subscriber's mail address as a query parameter, so an unrecognised wrapper loses
 * its whole query rather than keeping it.
 */
package de.codeministry.leadgen.ingest.extract;
