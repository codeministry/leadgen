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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The connections the chat may ask a model on: {@code llm}, which every stage uses, and the
 * optional {@code chat.connection} beside it.
 *
 * <p>Each one is mapped onto the {@link PipelineConfig.Llm} shape {@link ChatModels#of} already
 * takes, so building a model on the second connection is the same call as on the first, and the
 * rules about keys and base URLs in {@code Providers} hold for both without a second copy.
 *
 * <p><b>Whether a connection is billed is decided here and nowhere else.</b> An explicit
 * {@code billed: true} or {@code false} wins; absent means billed for the hosted kind only,
 * because an {@code openai-compatible} endpoint may be a local server or a paid one and only
 * the operator knows which.
 */
public final class Connections {

    /** The two names a connection goes by; stored on a turn, never its base URL. */
    public enum Name {
        PRIMARY,
        SECOND
    }

    /**
     * One connection, ready for {@link ChatModels#of}.
     *
     * @param name   which of the two it is
     * @param kind   the provider kind, as configured
     * @param llm    the connection in the shape every model factory takes
     * @param billed whether a call on it costs money, resolved by {@link #billed}
     */
    public record Connection(Name name, String kind, PipelineConfig.Llm llm, boolean billed) {}

    private Connections() {}

    /** The primary connection, {@code llm}, whatever it holds. */
    public static Connection primary(PipelineConfig pipeline) {
        PipelineConfig.Llm llm = pipeline.llm();
        return new Connection(Name.PRIMARY, llm.provider(), llm, billed(llm.provider(), llm.billed()));
    }

    /**
     * The second connection, present only when {@code chat.connection} names a provider kind.
     *
     * <p>It borrows the primary's {@code models} block because the shape requires one; nothing
     * on this connection reads a model from it, since the chat names the model per turn. No
     * {@code budget} and no {@code concurrency}: the second connection is metered by the chat's
     * ceilings and never by {@code llm.budget}.
     */
    public static Optional<Connection> second(PipelineConfig pipeline) {
        PipelineConfig.ChatConnection block =
                pipeline.chat() == null ? null : pipeline.chat().connection();
        if (block == null || !block.configured()) {
            return Optional.empty();
        }
        PipelineConfig.Llm llm = new PipelineConfig.Llm(
                block.provider(),
                block.baseUrl(),
                block.apiKey(),
                block.timeout(),
                false,
                pipeline.llm().models(),
                null,
                null,
                block.billed());
        return Optional.of(
                new Connection(Name.SECOND, block.provider(), llm, billed(block.provider(), block.billed())));
    }

    /** The connection going by {@code name}, when it is configured. */
    public static Optional<Connection> of(PipelineConfig pipeline, Name name) {
        return name == Name.PRIMARY ? Optional.of(primary(pipeline)) : second(pipeline);
    }

    /** Both connections, the primary first and the second only when configured. */
    public static List<Connection> all(PipelineConfig pipeline) {
        List<Connection> all = new ArrayList<>(2);
        all.add(primary(pipeline));
        second(pipeline).ifPresent(all::add);
        return List.copyOf(all);
    }

    /**
     * Whether a connection of {@code kind} is billed, given what its {@code billed} key says.
     *
     * <p>An explicit {@code true} or {@code false} wins, in any case. Anything else — absent,
     * empty — falls to the kind: billed for {@link ChatModels#ANTHROPIC}, not billed for every
     * other kind. {@code ConfigLoader} refuses a value that is neither, so a typo never reads as
     * "not billed" here.
     */
    public static boolean billed(String kind, String billed) {
        String value = billed == null ? "" : billed.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> ChatModels.ANTHROPIC.equals(kind);
        };
    }
}
