/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.codeministry.leadgen.LeadGenRuntimeHints;
import de.codeministry.leadgen.chat.catalog.ChatPrices;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigSource;
import de.codeministry.leadgen.config.ConfigValidationException;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import de.codeministry.leadgen.llm.Connections;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/**
 * ISC-516: the chat's price table, {@code chat.prices}, in {@code pipeline.yaml}.
 *
 * <p>Every case starts from the shipped file, so what is under test is the block that ships: the
 * table is written in place of its {@code prices: []} line, or the line is cut out entirely. No
 * Spring context — the claim is about what the loader binds and refuses, not about a turn.
 */
class ChatPriceConfigTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    /** The shipped key, exactly as it stands in the file. */
    private static final String SHIPPED_LINE = "  prices: []";

    @TempDir
    Path configDir;

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    @BeforeEach
    void materialize() {
        ConfigFixtures.materialize(configDir);
    }

    @Test
    void aTableForTwoConnectionsBindsEveryEntry() throws IOException {
        writePrices("""
                  prices:
                    - connection: PRIMARY
                      model: "local-model:20b"
                      input: 0
                      output: 0
                    - connection: SECOND
                      model: "hosted-model-4.5"
                      input: 3
                      output: 15.25
                """);

        List<PipelineConfig.ChatPrice> prices = load().chat().prices();

        assertThat(prices).hasSize(2);
        PipelineConfig.ChatPrice primary = prices.get(0);
        assertThat(primary.connection()).isEqualTo("PRIMARY");
        assertThat(primary.model()).isEqualTo("local-model:20b");
        assertThat(primary.input()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(primary.output()).isEqualByComparingTo(BigDecimal.ZERO);
        PipelineConfig.ChatPrice second = prices.get(1);
        assertThat(second.connection()).isEqualTo("SECOND");
        assertThat(second.model()).isEqualTo("hosted-model-4.5");
        assertThat(second.input()).isEqualByComparingTo("3");
        assertThat(second.output()).isEqualByComparingTo("15.25");
    }

    @Test
    void aNegativePriceIsRefusedWithALineNamingTheKey() throws IOException {
        writePrices("""
                  prices:
                    - connection: SECOND
                      model: "hosted-model"
                      input: -1
                      output: 15
                """);

        assertThatThrownBy(this::load)
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("chat.prices")
                .hasMessageContaining("input");
    }

    @Test
    void anEntryWithOneHalfMissingIsRefusedWithALineNamingTheKey() throws IOException {
        writePrices("""
                  prices:
                    - connection: SECOND
                      model: "hosted-model"
                      input: 3
                """);

        assertThatThrownBy(this::load)
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("chat.prices")
                .hasMessageContaining("hosted-model")
                .hasMessageContaining("output");
    }

    /**
     * An entry written as {@code - ~} binds as a null element. The record's own copy must keep it,
     * so the loader can name it; {@code List.copyOf} threw a bare NullPointerException instead.
     */
    @Test
    void anEmptyEntryIsRefusedWithALineNamingItsIndex() throws IOException {
        writePrices("""
                  prices:
                    - connection: SECOND
                      model: "hosted-model"
                      input: 3
                      output: 15
                    - ~
                """);

        assertThatThrownBy(this::load)
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("chat.prices[1] is empty");
    }

    @Test
    void anUnknownConnectionNameIsRefusedWithALineNamingTheKey() throws IOException {
        writePrices("""
                  prices:
                    - connection: THIRD
                      model: "hosted-model"
                      input: 3
                      output: 15
                """);

        assertThatThrownBy(this::load)
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("chat.prices")
                .hasMessageContaining("THIRD")
                .hasMessageContaining("PRIMARY")
                .hasMessageContaining("SECOND");
    }

    @Test
    void twoPricesForOneModelAreRefusedWithALineNamingTheKey() throws IOException {
        writePrices("""
                  prices:
                    - connection: SECOND
                      model: "hosted-model"
                      input: 3
                      output: 15
                    - connection: SECOND
                      model: "hosted-model"
                      input: 1
                      output: 5
                """);

        assertThatThrownBy(this::load)
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("chat.prices")
                .hasMessageContaining("hosted-model");
    }

    @Test
    void theShippedEmptyTableLoadsWithEveryModelUnpriced() {
        assertThat(load().chat().prices()).isNotNull().isEmpty();
    }

    @Test
    void anAbsentTableLoadsWithEveryModelUnpriced() throws IOException {
        Path pipeline = configDir.resolve("pipeline.yaml");
        String shipped = Files.readString(pipeline, StandardCharsets.UTF_8);
        String cut = shipped.replace(SHIPPED_LINE + "\n", "");
        assertThat(cut).as("the shipped line was cut out").isNotEqualTo(shipped);
        Files.writeString(pipeline, cut, StandardCharsets.UTF_8);

        assertThat(load().chat().prices()).isNotNull().isEmpty();
    }

    /**
     * The shipped block, read the way {@code rg} would: the key is there and empty, its comment
     * names the shape and the unit, and neither carries a price or a model's name — a number or a
     * name in the committed file would be a vendor's list price in the code.
     */
    @Test
    void theShippedBlockNamesTheKeyWithNoPriceAndNoModel() {
        String shipped =
                ConfigSource.fromClasspath("pipeline.yaml").orElseThrow().content();

        assertThat(shipped).containsPattern("(?m)^" + Pattern.quote(SHIPPED_LINE) + "$");
        List<String> comment = commentAbove(shipped);
        String joined = String.join("\n", comment);
        assertThat(joined)
                .contains("USD per million")
                .contains("connection: SECOND")
                .contains("model: \"<name>\"")
                .contains("input:")
                .contains("output:");
        assertThat(joined).as("no price in the shipped comment").doesNotContainPattern("\\d");
        assertThat(comment)
                .filteredOn(line -> line.contains("model:"))
                .allSatisfy(line -> assertThat(line).contains("model: \"<name>\""));
    }

    @Test
    void anEntryIsHintedForTheNativeImage() {
        RuntimeHints hints = new RuntimeHints();
        new LeadGenRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(PipelineConfig.ChatPrice.class)
                        .withMemberCategory(MemberCategory.ACCESS_DECLARED_FIELDS))
                .accepts(hints);
    }

    /**
     * A turn asked without a pick runs under the configured name, {@code local-model}, while an
     * Ollama connection lists — and the catalog prices — {@code local-model:latest}. The turn is
     * costed at the price the catalog shows for it, by the same equivalence that flags it the
     * default: 1M in × 1 + 1M out × 2 = 3 USD on a billed Ollama primary.
     */
    @Test
    void aDefaultTurnIsCostedAtThePriceOfItsListedName() throws IOException {
        Path pipeline = configDir.resolve("pipeline.yaml");
        String yaml = Files.readString(pipeline, StandardCharsets.UTF_8)
                .replaceFirst("(?m)^  provider: \\$\\{LLM_PROVIDER:}", "  provider: ollama")
                .replaceFirst("(?m)^  base_url: \\$\\{LLM_BASE_URL:}$", "  base_url: http://127.0.0.1:1")
                .replaceFirst("(?m)^  billed: \\$\\{LLM_BILLED:}$", "  billed: true")
                .replaceFirst("(?m)^    chat: \\$\\{LLM_MODEL_CHAT}", "    chat: local-model");
        Files.writeString(pipeline, yaml, StandardCharsets.UTF_8);
        writePrices("""
                  prices:
                    - connection: PRIMARY
                      model: "local-model:latest"
                      input: 1
                      output: 2
                """);
        PipelineConfig loaded = load();
        assertThat(loaded.llm().models().chat()).isEqualTo("local-model");

        TurnPrice price = TurnPrice.of(loaded, Connections.Name.PRIMARY, "local-model");

        assertThat(price.cost(new TurnUsage(1_000_000, 1_000_000)))
                .as("the default turn costed at the price of local-model:latest")
                .isNotNull()
                .isEqualByComparingTo("3");
        assertThat(ChatPrices.listed(ChatModels.OLLAMA, "local-model:8b"))
                .as("a tagged name is listed as it is")
                .isEqualTo("local-model:8b");
        assertThat(ChatPrices.listed(ChatModels.OPENAI_COMPATIBLE, "local-model"))
                .as("only Ollama tags a bare name")
                .isEqualTo("local-model");
    }

    /**
     * An Ollama entry written without its tag prices the name the connection lists, {@code :latest}:
     * the table's key goes through the same {@link ChatPrices#listed} rule as every lookup, so the
     * entry is found both for the configured bare name and for the tagged one the catalog shows.
     */
    @Test
    void anUntaggedOllamaEntryPricesTheListedName() throws IOException {
        billedOllamaPrimary();
        writePrices("""
                  prices:
                    - connection: PRIMARY
                      model: "local-model"
                      input: 1
                      output: 2
                """);
        PipelineConfig loaded = load();

        assertThat(TurnPrice.of(loaded, Connections.Name.PRIMARY, "local-model")
                        .cost(new TurnUsage(1_000_000, 1_000_000)))
                .as("a turn on the bare name is costed at the untagged entry")
                .isNotNull()
                .isEqualByComparingTo("3");
        assertThat(ChatPrices.of(loaded, "PRIMARY", ChatModels.OLLAMA, "local-model:latest"))
                .as("the catalog's tagged name finds the untagged entry")
                .isPresent();
    }

    /** On Ollama {@code name} and {@code name:latest} are one model, so two entries for them are a duplicate. */
    @Test
    void anUntaggedAndALatestEntryForOneOllamaModelAreRefusedAsTwoPrices() throws IOException {
        billedOllamaPrimary();
        writePrices("""
                  prices:
                    - connection: PRIMARY
                      model: "local-model"
                      input: 1
                      output: 2
                    - connection: PRIMARY
                      model: "local-model:latest"
                      input: 3
                      output: 4
                """);

        assertThatThrownBy(this::load)
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("chat.prices[1]")
                .hasMessageContaining("a second time");
    }

    /** The materialized primary as a billed Ollama connection whose chat model is {@code local-model}. */
    private void billedOllamaPrimary() throws IOException {
        Path pipeline = configDir.resolve("pipeline.yaml");
        String yaml = Files.readString(pipeline, StandardCharsets.UTF_8)
                .replaceFirst("(?m)^  provider: \\$\\{LLM_PROVIDER:}", "  provider: ollama")
                .replaceFirst("(?m)^  base_url: \\$\\{LLM_BASE_URL:}$", "  base_url: http://127.0.0.1:1")
                .replaceFirst("(?m)^  billed: \\$\\{LLM_BILLED:}$", "  billed: true")
                .replaceFirst("(?m)^    chat: \\$\\{LLM_MODEL_CHAT}", "    chat: local-model");
        Files.writeString(pipeline, yaml, StandardCharsets.UTF_8);
    }

    private PipelineConfig load() {
        return ConfigFixtures.loaderFor(configDir, VALIDATOR).load().application();
    }

    /** The materialized {@code pipeline.yaml} with its shipped {@code prices: []} line replaced. */
    private void writePrices(String block) throws IOException {
        Path pipeline = configDir.resolve("pipeline.yaml");
        String shipped = Files.readString(pipeline, StandardCharsets.UTF_8);
        String written = shipped.replace(SHIPPED_LINE + "\n", block);
        assertThat(written).as("the shipped line was replaced").isNotEqualTo(shipped);
        Files.writeString(pipeline, written, StandardCharsets.UTF_8);
    }

    /** The comment lines directly above {@code chat.prices}, with their leading {@code #} kept. */
    private static List<String> commentAbove(String yaml) {
        Matcher block = Pattern.compile("(?m)((?:^  #.*\\R)+)^" + Pattern.quote(SHIPPED_LINE) + "$")
                .matcher(yaml);
        assertThat(block.find()).as("a comment documents chat.prices").isTrue();
        List<String> lines = new ArrayList<>();
        for (String line : block.group(1).split("\\R")) {
            lines.add(line.strip());
        }
        return lines;
    }
}
