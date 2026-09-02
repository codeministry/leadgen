/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * Fetching the original advert. The only stage that leaves the machine.
 *
 * <p>It exists because of one measurement: the sources state an hourly rate in 0.0 % of
 * offers and a remote share in 8.8 %. Almost everything worth filtering on is not in the
 * listing. It runs after the hard filter, because fetching a thousand adverts to then discard
 * eight hundred would be rude to the portals and slow for nothing.
 *
 * <p><b>It never discards.</b> A fetch that is forbidden, rate-limited, unreachable or
 * unreadable leaves the offer in the pipeline with a note saying why. Scoring then judges an
 * incomplete offer as incomplete, which somebody can review; an offer that quietly stopped
 * existing cannot be.
 *
 * <p>Four gates, cheapest first: cache, {@code robots.txt}, rate limit, network. Failures are
 * cached and timeouts are not, because a 403 is a fact about the page and a timeout is a fact
 * about the moment. Every enriched column is nullable, and <b>null means "not stated", never
 * zero</b>.
 */
package de.codeministry.leadgen.enrich;
