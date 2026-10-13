/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.catalog;

import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import de.codeministry.leadgen.llm.Connections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one rule that finds a model's price in {@code chat.prices} (spec 026): the entry naming
 * exactly that connection and the model under its {@link #listed} name, the first one where two do. The catalog's view (ISC-517)
 * and a turn's cost (ISC-519) both read it here, so a model shown with a price is costed at it.
 */
public final class ChatPrices {

    private ChatPrices() {}

    /**
     * Every complete entry of {@code pipeline}'s price table, keyed by connection and model name —
     * the name as its connection lists it, by the same {@link #listed} rule every lookup goes
     * through, so an Ollama entry written {@code name} prices {@code name:latest}. The loader
     * refuses a half-filled entry; a hand-built configuration is held to the same rule.
     */
    public static Map<List<String>, PipelineConfig.ChatPrice> table(PipelineConfig pipeline) {
        Map<List<String>, PipelineConfig.ChatPrice> table = new HashMap<>();
        PipelineConfig.Chat chat = pipeline == null ? null : pipeline.chat();
        List<PipelineConfig.ChatPrice> prices = chat == null || chat.prices() == null ? List.of() : chat.prices();
        for (PipelineConfig.ChatPrice entry : prices) {
            if (entry != null && entry.input() != null && entry.output() != null) {
                String kind = kind(pipeline, entry.connection()).orElse(null);
                table.putIfAbsent(key(entry.connection(), listed(kind, entry.model())), entry);
            }
        }
        return table;
    }

    /**
     * The price {@code pipeline} names for {@code model} on {@code connection} of {@code kind}, or
     * none. The model is looked up under its {@link #listed} name — the one the catalog shows and
     * prices — so a turn asked under the configured {@code name} is costed at the price shown for
     * {@code name:latest}.
     */
    public static Optional<PipelineConfig.ChatPrice> of(
            PipelineConfig pipeline, String connection, String kind, String model) {
        return in(table(pipeline), connection, kind, model);
    }

    /**
     * The price {@code table} — one {@link #table} built — holds for {@code model} on
     * {@code connection} of {@code kind}, or none: {@link #of} for a caller that looks up many
     * models against one table, by the same key.
     */
    public static Optional<PipelineConfig.ChatPrice> in(
            Map<List<String>, PipelineConfig.ChatPrice> table, String connection, String kind, String model) {
        return Optional.ofNullable(table.get(key(connection, listed(kind, model))));
    }

    /**
     * The name a connection of {@code kind} lists {@code model} under. Ollama reports every model
     * with its tag, so a configured name without one is listed as {@code name:latest}; every other
     * kind lists a name as it is. The catalog flags the default, a turn finds its price, the table
     * keys its entries and the loader finds a duplicate by this one rule.
     */
    public static String listed(String kind, String model) {
        if (model == null || !ChatModels.OLLAMA.equals(kind)) {
            return model;
        }
        // A registry host's port is a colon too; only one after the last slash is a tag.
        return model.substring(model.lastIndexOf('/') + 1).contains(":") ? model : model + ":latest";
    }

    /**
     * The provider kind of the connection {@code pipeline} configures under the name
     * {@code connection}, or none when that connection is not configured — an entry for it is then
     * keyed as written, and never looked up.
     */
    public static Optional<String> kind(PipelineConfig pipeline, String connection) {
        if (pipeline == null || pipeline.llm() == null) {
            return Optional.empty();
        }
        return Connections.all(pipeline).stream()
                .filter(configured -> configured.name().name().equals(connection))
                .findFirst()
                // A connection without a provider kind (the shipped placeholder left empty) has none.
                .map(Connections.Connection::kind);
    }

    /** The table's key for {@code model} on {@code connection}. */
    private static List<String> key(String connection, String model) {
        return List.of(String.valueOf(connection), String.valueOf(model));
    }
}
