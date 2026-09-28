/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
/**
 * leadgen's MCP server (spec 023): its read surface as MCP tools at {@code /mcp}, in the api's own
 * process and under its own security chain.
 *
 * <p>The six {@code leadgen_*} tools codeministry-mcp served keep their names, parameters and answer
 * shapes (the moved {@code baseline-tools.json} pins them), rebuilt typed on the services the REST
 * controllers call rather than over HTTP; four of the chat's tools join them without a chat turn.
 * Nothing here writes, and nothing leaves the process unmasked.
 */
package de.codeministry.leadgen.mcp;
