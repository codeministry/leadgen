/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

import de.codeministry.leadgen.chat.ToolOutputMasker;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every MCP tool result passes the chat's {@link ToolOutputMasker} before it leaves the process
 * (spec 023, ISC-485): the configured mailbox address and every value the startup banner masks.
 *
 * <p><b>Around the tool list, not inside each tool.</b> The MCP server reads its tools from one
 * list of specifications; this wraps every handler in it as the list is created, so a tool added
 * later is masked without anybody remembering to. The whole serialised answer is masked, text and
 * structured content alike, never selected fields, so a new field cannot slip past either.
 *
 * <p>A handler that throws answers as an error result with the masked message, as the chat does,
 * rather than as a protocol error carrying the exception's text unmasked.
 *
 * <p>The masker and the mapper arrive through providers and are resolved at the first call: a
 * post-processor is created before ordinary beans, and pulling the configuration in that early
 * would take it out of the reach of the other post-processors.
 */
@Component
class MaskedResults implements BeanPostProcessor {

    private final ObjectProvider<ToolOutputMasker> masker;
    private final ObjectProvider<JsonMapper> json;

    MaskedResults(ObjectProvider<ToolOutputMasker> masker, ObjectProvider<JsonMapper> json) {
        this.masker = masker;
        this.json = json;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof List<?> list
                && !list.isEmpty()
                && list.stream().allMatch(SyncToolSpecification.class::isInstance)) {
            return list.stream()
                    .map(SyncToolSpecification.class::cast)
                    .map(this::masked)
                    .toList();
        }
        return bean;
    }

    private SyncToolSpecification masked(SyncToolSpecification tool) {
        var handler = tool.callHandler();
        return new SyncToolSpecification(tool.tool(), (exchange, request) -> {
            CallToolResult result;
            try {
                result = handler.apply(exchange, request);
            } catch (RuntimeException e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                return CallToolResult.builder()
                        .content(List.of(new TextContent(mask(message))))
                        .isError(true)
                        .build();
            }
            return mask(result);
        });
    }

    private CallToolResult mask(CallToolResult result) {
        if (result == null) {
            return null;
        }
        List<Content> content = result.content() == null
                ? null
                : result.content().stream().map(this::mask).toList();
        Object structured = result.structuredContent() == null
                ? null
                : json.getObject()
                        .readValue(mask(json.getObject().writeValueAsString(result.structuredContent())), Object.class);
        return new CallToolResult(content, result.isError(), structured, result.meta());
    }

    private Content mask(Content content) {
        if (content instanceof TextContent text) {
            return new TextContent(text.annotations(), mask(text.text()), text.meta());
        }
        return content;
    }

    private String mask(String text) {
        return masker.getObject().mask(text);
    }
}
