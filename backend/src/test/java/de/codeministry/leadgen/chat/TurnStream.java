/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Asks a question over real HTTP and reads the turn's server-sent events as they arrive, each
 * stamped with the moment it was read.
 *
 * <p>Real HTTP rather than MockMvc because MockMvc hands an async response over only once it is
 * complete, and "the first text before the model's last chunk" is a claim about arrival times.
 */
final class TurnStream {

    /** One event off the wire: its name, its JSON data and when the test read it. */
    record Event(String name, String data, Instant at) {}

    private TurnStream() {}

    static List<Event> ask(int port, long conversation, String question) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/api/v1/chat/conversations/" + conversation + "/turns"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"" + question.replace("\"", "\\\"") + "\"}"))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<java.util.stream.Stream<String>> response =
                    client.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("turn answered " + response.statusCode());
            }
            List<Event> events = new ArrayList<>();
            String name = null;
            StringBuilder data = new StringBuilder();
            Iterator<String> lines = response.body().iterator();
            while (lines.hasNext()) {
                String line = lines.next();
                if (line.startsWith("event:")) {
                    name = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5));
                } else if (line.isEmpty() && name != null) {
                    events.add(new Event(name, data.toString(), Instant.now()));
                    name = null;
                    data.setLength(0);
                }
            }
            return events;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * POSTs {@code body} to {@code path} and reads the events as {@link #ask} does, handing each to
     * {@code onEvent} the moment it is read — for a test that acts in the middle of a stream.
     */
    static List<Event> post(int port, String path, String body, java.util.function.Consumer<Event> onEvent) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<java.util.stream.Stream<String>> response =
                    client.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(path + " answered " + response.statusCode());
            }
            List<Event> events = new ArrayList<>();
            String name = null;
            StringBuilder data = new StringBuilder();
            Iterator<String> lines = response.body().iterator();
            while (lines.hasNext()) {
                String line = lines.next();
                if (line.startsWith("event:")) {
                    name = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5));
                } else if (line.isEmpty() && name != null) {
                    Event event = new Event(name, data.toString(), Instant.now());
                    events.add(event);
                    onEvent.accept(event);
                    name = null;
                    data.setLength(0);
                }
            }
            return events;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A plain POST without a body to read; answers the status. */
    static int postStatus(int port, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static List<String> names(List<Event> events) {
        return events.stream().map(Event::name).toList();
    }

    /** The {@code delta} of every {@code text} event, joined. */
    static String text(List<Event> events) {
        StringBuilder text = new StringBuilder();
        for (Event event : events) {
            if (event.name().equals("text")) {
                text.append(JsonText.field(event.data(), "delta"));
            }
        }
        return text.toString();
    }

    /** A string field of a flat JSON object, read with Jackson. */
    static final class JsonText {
        private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
                new com.fasterxml.jackson.databind.ObjectMapper();

        private JsonText() {}

        static String field(String json, String name) {
            try {
                return JSON.readTree(json).path(name).asText();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
