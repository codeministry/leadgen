/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.llm;

import de.codeministry.leadgen.config.model.PipelineConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * Whether a configuration can reach a model at all, asked the same way for every kind of
 * model there is.
 *
 * <p>It is one method because the answer is three rules that were each learned once, and a
 * second copy of them would relearn them: a local provider needs no key, a base URL is
 * required even for a vendor whose address never changes, and every refusal is a quiet
 * {@code false} or a WARN rather than an exception — a missing key must leave the tool
 * running, only weaker.
 */
@Slf4j
final class Providers {

    private Providers() {
    }

    /**
     * True when {@code model} is worth trying to build under {@code llm}.
     *
     * <p>Nothing here knows which wire formats are implemented; that is the caller's, because
     * chat and embeddings do not support the same ones.
     */
    static boolean reachable(PipelineConfig.Llm llm, String model) {
        if (llm == null || blank(llm.provider()) || blank(model)) {
            return false;
        }
        // A key is what a hosted provider needs and a local one does not. Requiring it
        // everywhere made `provider: ollama` unusable: a local server wants no key, so there
        // was nothing to write in `.env`, and nothing was ever built.
        if (blank(llm.apiKey()) && !ChatModels.OLLAMA.equals(llm.provider())) {
            return false;
        }
        if (blank(llm.baseUrl())) {
            // Required even for a hosted provider whose address never changes: a URL in the
            // code is a vendor in the code, and this repository has none.
            log.warn("llm.base_url is not set; there is nowhere to send a request");
            return false;
        }
        return true;
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
