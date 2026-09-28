/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen;

import de.codeministry.leadgen.chat.BulkDelete;
import de.codeministry.leadgen.chat.BulkDeleted;
import de.codeministry.leadgen.chat.ChatCapabilityView;
import de.codeministry.leadgen.chat.ChatContextItem;
import de.codeministry.leadgen.chat.ChatDone;
import de.codeministry.leadgen.chat.ChatError;
import de.codeministry.leadgen.chat.ChatSource;
import de.codeministry.leadgen.chat.ChatSources;
import de.codeministry.leadgen.chat.ChatStatusView;
import de.codeministry.leadgen.chat.ChatStep;
import de.codeministry.leadgen.chat.ChatText;
import de.codeministry.leadgen.chat.ChatTurnStarted;
import de.codeministry.leadgen.chat.ConversationSummary;
import de.codeministry.leadgen.chat.ConversationView;
import de.codeministry.leadgen.chat.NewContext;
import de.codeministry.leadgen.chat.NewConversation;
import de.codeministry.leadgen.chat.NewTurn;
import de.codeministry.leadgen.chat.PastTurn;
import de.codeministry.leadgen.chat.RenameConversation;
import de.codeministry.leadgen.chat.StatisticsSource;
import de.codeministry.leadgen.chat.TurnLedger;
import de.codeministry.leadgen.chat.TurnView;
import de.codeministry.leadgen.chat.suggest.FollowUp;
import de.codeministry.leadgen.chat.suggest.Suggestion;
import de.codeministry.leadgen.chat.suggest.SuggestionCandidate;
import de.codeministry.leadgen.chat.suggest.SuggestionScope;
import de.codeministry.leadgen.chat.suggest.SuggestionThresholds;
import de.codeministry.leadgen.chat.tools.ApplicationTool;
import de.codeministry.leadgen.chat.tools.OfferSearchTool;
import de.codeministry.leadgen.chat.tools.PinnedAdvert;
import de.codeministry.leadgen.chat.tools.PinnedContext;
import de.codeministry.leadgen.chat.tools.PinnedOfferResult;
import de.codeministry.leadgen.chat.tools.ProfileTool;
import de.codeministry.leadgen.chat.tools.SemanticSearchTool;
import de.codeministry.leadgen.chat.tools.StatisticsTool;
import java.lang.reflect.Method;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Everything this application reaches by a name it computes at runtime.
 *
 * <h2>Why any of this is needed</h2>
 *
 * <p>A native image keeps a resource only if something at build time said it would be wanted.
 * Spring's own AOT processing finds most of them by following the code, and it cannot follow
 * these: {@code ConfigSource.fromClasspath} builds {@code "/leadgen/" + name} from a file name
 * that arrives out of {@code pipeline.yaml}, and {@code PackagingService} renders
 * {@code templates/cover-letter.{lang}.ftl} with the language decided per offer. There is no
 * literal to follow in either case.
 *
 * <h2>Why it is worth a class rather than a shrug</h2>
 *
 * <p>The failure is not a build error and usually not a startup error. A missing resource comes
 * back as an empty {@code Optional}, which is the same answer as "the operator did not override
 * this file" — so the application starts, reports healthy, falls back to nothing, and the first
 * sign of it is a stage that quietly did no work. That is the one defect class this file exists
 * against, and it is why {@code LeadGenRuntimeHintsTest} asserts against the directory's actual
 * contents rather than against a list repeated here.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>FreeMarker's own {@code version.properties} and {@code unsafeMethods.properties}, jsoup,
 * flyway, postgresql, snakeyaml and angus-mail all come from the GraalVM reachability-metadata
 * repository, whose version travels with the {@code native-build-tools} plugin. Copying them
 * here would be a second opinion that goes stale on its own schedule.
 * {@code docs/decisions/native-image.md} carries that reasoning and the rest of the native
 * story.
 */
public class LeadGenRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // Patterns and not seven names, because the set is "whatever ships under leadgen/" and
        // a list here would be a second inventory to keep in step with the directory.
        hints.resources().registerPattern("leadgen/*.yaml");
        hints.resources().registerPattern("leadgen/templates/*.ftl");
        // The chat's system prompt, read by name when a turn starts (ISC-437), and the suggestion
        // prompt, read by name when the empty chat or a finished answer asks for questions (ISC-456).
        hints.resources().registerPattern("leadgen/*.st");
        // The suggestion catalog, one file per language, the name computed from Accept-Language.
        hints.resources().registerPattern("leadgen/i18n/*.properties");
        registerChatTools(hints);
        registerChatRecords(hints);
    }

    /**
     * Every record the chat hands to Jackson or takes from it: the server-sent events, the
     * conversation views, the request bodies, the ledger's rows kept in {@code chat_turn.citations}
     * and the pinned offer's result, which no {@code @Tool} method returns.
     *
     * <p>{@code SseEmitter} and the repository serialise these by reflection, which AOT cannot
     * follow; in a native image a missing one is an empty object on the wire, not an error. Named
     * rather than scanned for the reason {@link #registerChatTools} gives, and {@code
     * LeadGenRuntimeHintsTest} scans the package, so a new record without an entry here fails it.
     */
    private static void registerChatRecords(RuntimeHints hints) {
        BindingReflectionHintsRegistrar bindings = new BindingReflectionHintsRegistrar();
        bindings.registerReflectionHints(
                hints.reflection(),
                BulkDelete.class,
                BulkDeleted.class,
                ChatCapabilityView.class,
                ChatContextItem.class,
                ChatDone.class,
                ChatError.class,
                ChatSource.class,
                ChatSources.class,
                ChatStatusView.class,
                ChatStep.class,
                ChatText.class,
                ChatTurnStarted.class,
                ConversationSummary.class,
                ConversationView.class,
                NewContext.class,
                FollowUp.class,
                Suggestion.class,
                SuggestionCandidate.class,
                SuggestionScope.class,
                SuggestionThresholds.class,
                NewConversation.class,
                NewTurn.class,
                PastTurn.class,
                RenameConversation.class,
                StatisticsSource.class,
                StatisticsSource.Row.class,
                StatisticsSource.SeriesDay.class,
                TurnView.class,
                TurnLedger.Call.class,
                TurnLedger.Citation.class,
                TurnLedger.Ref.class,
                PinnedOfferResult.class,
                PinnedAdvert.class,
                // Never serialised; hinted because the guard binds every public chat record.
                PinnedContext.class);
    }

    /**
     * The chat's tools, which the model reaches by the name in their {@code @Tool} annotation.
     *
     * <p>Spring AI finds those methods by reflection and invokes them the same way, and the result
     * record goes to JSON through its accessors — none of it a call AOT can follow. The parameters
     * are bound too, because the JSON schema the model reads is generated from them. Named here
     * rather than scanned, because a scan at build time would hint whatever it found and the test
     * could no longer tell a tool that was meant to be reachable from one that merely was;
     * {@code LeadGenRuntimeHintsTest} scans instead, so a tool missing from this list fails it.
     */
    private static void registerChatTools(RuntimeHints hints) {
        BindingReflectionHintsRegistrar bindings = new BindingReflectionHintsRegistrar();
        for (Class<?> tool : List.of(
                OfferSearchTool.class,
                SemanticSearchTool.class,
                StatisticsTool.class,
                ApplicationTool.class,
                ProfileTool.class)) {
            for (Method method : tool.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    hints.reflection().registerMethod(method, ExecutableMode.INVOKE);
                    bindings.registerReflectionHints(hints.reflection(), method.getGenericReturnType());
                    bindings.registerReflectionHints(hints.reflection(), method.getGenericParameterTypes());
                }
            }
        }
    }
}
