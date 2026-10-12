/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Messages-API endpoint for the chat's second connection (spec 025, ISC-505, ISC-507): it lists
 * models at {@code GET /v1/models} and streams one scripted text answer per {@code POST
 * /v1/messages}, the event sequence a hosted model sends — {@code message_start},
 * {@code content_block_start}, a {@code text_delta} per part, {@code content_block_stop},
 * {@code message_delta}, {@code message_stop}.
 *
 * <p>A raw socket in the style of {@link ModelStub}, so the request bodies are read exactly as they
 * left the client. {@link #baseUrl()} is the root without {@code /v1}: the client appends
 * {@code /v1/messages} to its base URL itself, and the catalog's listing adds {@code /v1} where the
 * base does not end in it. No vendor URL and no real model name anywhere.
 */
final class AnthropicStub implements AutoCloseable {

    private final ServerSocket server;
    private final ConcurrentLinkedQueue<List<String>> replies = new ConcurrentLinkedQueue<>();
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<List<String>> listed = new AtomicReference<>(List.of());
    private final AtomicInteger listings = new AtomicInteger();

    private AnthropicStub(ServerSocket server) {
        this.server = server;
        Thread.ofVirtual().start(this::serve);
    }

    static AnthropicStub start() {
        try {
            return new AnthropicStub(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The root the connection's {@code base_url} names; the client adds {@code /v1/messages}. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    /** The models {@code GET /v1/models} lists from now on, on one page. */
    void lists(String... models) {
        listed.set(List.of(models));
    }

    /** The next {@code POST /v1/messages} streams {@code parts} as text and ends the message. */
    void enqueueText(String... parts) {
        replies.add(List.of(parts));
    }

    /** Every {@code POST} body the stub received, in order; listings are not among them. */
    List<String> bodies() {
        synchronized (bodies) {
            return List.copyOf(bodies);
        }
    }

    /** The path of every {@code POST}, in order. */
    List<String> paths() {
        synchronized (paths) {
            return List.copyOf(paths);
        }
    }

    /** How many times the model list was asked for. */
    int listings() {
        return listings.get();
    }

    void reset() {
        replies.clear();
        bodies.clear();
        paths.clear();
        listed.set(List.of());
        listings.set(0);
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // Nothing is listening any more either way.
        }
    }

    private void serve() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread.ofVirtual().start(() -> answer(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void answer(Socket socket) {
        try (socket) {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String[] requestLine = readLine(in).split(" ");
            String body = readBody(in);
            String method = requestLine.length > 0 ? requestLine[0] : "";
            String path = requestLine.length > 1 ? requestLine[1] : "";
            OutputStream out = socket.getOutputStream();
            if (method.equals("GET") && path.startsWith("/v1/models")) {
                listings.incrementAndGet();
                json(out, models());
                return;
            }
            bodies.add(body);
            paths.add(path);
            List<String> reply = replies.poll();
            if (!method.equals("POST") || !path.startsWith("/v1/messages") || reply == null) {
                out.write(("HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json\r\n"
                                + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                return;
            }
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n"
                            + "Connection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String model = field(body, "model");
            event(
                    out,
                    "message_start",
                    "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_stub\","
                            + "\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":" + quote(model)
                            + ",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,"
                            + "\"output_tokens\":1,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}}");
            event(
                    out,
                    "content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":0,"
                            + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
            for (String part : reply) {
                event(
                        out,
                        "content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,"
                                + "\"delta\":{\"type\":\"text_delta\",\"text\":" + quote(part) + "}}");
            }
            event(out, "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
            event(
                    out,
                    "message_delta",
                    "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
                            + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":" + reply.size() + "}}");
            event(out, "message_stop", "{\"type\":\"message_stop\"}");
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException ignored) {
            // The client went away; the test reads what arrived.
        }
    }

    private String models() {
        StringBuilder data = new StringBuilder();
        List<String> models = listed.get();
        for (String model : models) {
            data.append(data.isEmpty() ? "" : ",")
                    .append("{\"type\":\"model\",\"id\":")
                    .append(quote(model))
                    .append(",\"display_name\":")
                    .append(quote(model))
                    .append(",\"created_at\":\"2026-01-01T00:00:00Z\"}");
        }
        String first = models.isEmpty() ? "null" : quote(models.getFirst());
        String last = models.isEmpty() ? "null" : quote(models.getLast());
        return "{\"data\":[" + data + "],\"has_more\":false,\"first_id\":" + first + ",\"last_id\":" + last + "}";
    }

    private static void json(OutputStream out, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length
                        + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static void event(OutputStream out, String name, String data) throws IOException {
        byte[] bytes = ("event: " + name + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
        out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** A top-level string field of the request body, or an empty string. */
    private static String field(String json, String name) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(json)
                    .path(name)
                    .asText("");
        } catch (IOException e) {
            return "";
        }
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String readBody(InputStream in) throws IOException {
        int length = -1;
        boolean chunked = false;
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(15).strip());
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (chunked) {
            int size;
            while ((size = Integer.parseInt(readLine(in).strip(), 16)) > 0) {
                body.write(in.readNBytes(size));
                readLine(in);
            }
            readLine(in);
        } else if (length > 0) {
            body.write(in.readNBytes(length));
        }
        return body.toString(StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }
}
