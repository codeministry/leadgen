/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import de.codeministry.leadgen.config.ConfigFixtures;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An OpenAI-compatible chat endpoint that streams scripted replies, one per request, with a
 * fixed pause between chunks — and can break a reply off mid-stream.
 *
 * <p>A raw socket rather than WireMock because the two things under test are timing and a
 * broken connection: WireMock's dribble delay cuts a body at byte counts rather than at event
 * boundaries, and its faults cannot first send two good chunks and then drop the line. Here each
 * chunk is one {@code chat.completion.chunk} event, flushed, and a break-off resets the socket
 * without the terminating chunk, which is what a model server dying mid-answer looks like to
 * the client.
 */
final class ModelStub implements AutoCloseable {

    /** The model name the materialised configuration names for the chat; no vendor anywhere. */
    static final String MODEL = "test-chat-model";

    /** One scripted reply: the events' {@code data} bodies, the pause before each, and whether it breaks off. */
    record Reply(List<String> events, Duration pause, boolean breaksOff) {}

    private final ServerSocket server;
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<Instant> lastChunkAt = new AtomicReference<>();
    private final AtomicReference<Instant> brokenAt = new AtomicReference<>();

    private ModelStub(ServerSocket server) {
        this.server = server;
        Thread.ofVirtual().start(this::serve);
    }

    static ModelStub start() {
        try {
            return new ModelStub(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/v1";
    }

    void enqueue(Reply reply) {
        replies.add(reply);
    }

    void reset() {
        replies.clear();
        bodies.clear();
        lastChunkAt.set(null);
        brokenAt.set(null);
    }

    /** Every request body the stub received, in order. */
    List<String> bodies() {
        synchronized (bodies) {
            return List.copyOf(bodies);
        }
    }

    /** When a write to the client first failed because the client had closed the connection; null if never. */
    Instant brokenAt() {
        return brokenAt.get();
    }

    /** When the last chunk of the last reply was about to be written. */
    Instant lastChunkAt() {
        return lastChunkAt.get();
    }

    /** A reply that streams {@code parts} as text and then finishes normally. */
    static Reply text(Duration pause, String... parts) {
        List<String> events = new ArrayList<>();
        for (String part : parts) {
            events.add(chunk("{\"role\":\"assistant\",\"content\":" + quote(part) + "}", null));
        }
        events.add(chunk("{}", "stop"));
        return new Reply(events, pause, false);
    }

    /** A reply that streams {@code parts} and then drops the connection without finishing. */
    static Reply brokenText(Duration pause, String... parts) {
        List<String> events = new ArrayList<>();
        for (String part : parts) {
            events.add(chunk("{\"role\":\"assistant\",\"content\":" + quote(part) + "}", null));
        }
        return new Reply(events, pause, true);
    }

    /** A reply asking for the named tools, each with its JSON arguments: {@code name, args, name, args, …}. */
    static Reply toolCalls(String... nameThenArguments) {
        StringBuilder calls = new StringBuilder();
        for (int i = 0; i < nameThenArguments.length; i += 2) {
            if (i > 0) {
                calls.append(',');
            }
            calls.append("{\"index\":")
                    .append(i / 2)
                    .append(",\"id\":\"call_")
                    .append(i / 2)
                    .append("\",\"type\":\"function\",\"function\":{\"name\":")
                    .append(quote(nameThenArguments[i]))
                    .append(",\"arguments\":")
                    .append(quote(nameThenArguments[i + 1]))
                    .append("}}");
        }
        return new Reply(
                List.of(
                        chunk("{\"role\":\"assistant\",\"tool_calls\":[" + calls + "]}", null),
                        chunk("{}", "tool_calls")),
                Duration.ZERO,
                false);
    }

    /** One round that says {@code text} and then asks for the named tools, as a model that narrates does. */
    static Reply textThenToolCalls(String text, String... nameThenArguments) {
        List<String> events = new ArrayList<>();
        events.add(chunk("{\"role\":\"assistant\",\"content\":" + quote(text) + "}", null));
        events.addAll(toolCalls(nameThenArguments).events());
        return new Reply(events, Duration.ZERO, false);
    }

    /** A configuration directory with the shipped defaults, pointed at this stub for the chat. */
    Path configuration() {
        try {
            Path dir = Files.createTempDirectory("leadgen-chat");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            Path pipeline = dir.resolve("pipeline.yaml");
            Files.writeString(
                    pipeline,
                    Files.readString(pipeline, StandardCharsets.UTF_8)
                            .replaceAll("(?m)^(\\s*)provider:.*$", "$1provider: openai-compatible")
                            .replaceAll("(?m)^(\\s*)base_url:.*$", "$1base_url: " + baseUrl())
                            .replaceAll("(?m)^(\\s*)api_key:.*$", "$1api_key: test-key")
                            .replaceAll("(?m)^(\\s*)chat: \\$\\{LLM_MODEL_CHAT}.*$", "$1chat: " + MODEL),
                    StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // Nothing is listening any more either way.
        }
    }

    private static String chunk(String delta, String finishReason) {
        return "{\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"" + MODEL
                + "\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":"
                + (finishReason == null ? "null" : quote(finishReason)) + "}]}";
    }

    private static String quote(String text) {
        // A NUL is written as its JSON escape, the way a real model's server sends one.
        return "\""
                + text.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\0", "\\u0000") + "\"";
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
            bodies.add(readBody(in));
            Reply reply = replies.poll();
            OutputStream out = socket.getOutputStream();
            if (reply == null) {
                out.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                return;
            }
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n"
                            + "Connection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            for (int i = 0; i < reply.events().size(); i++) {
                Thread.sleep(reply.pause());
                if (i == reply.events().size() - 1) {
                    lastChunkAt.set(Instant.now());
                }
                writeChunk(out, "data: " + reply.events().get(i) + "\n\n");
            }
            if (reply.breaksOff()) {
                Thread.sleep(reply.pause());
                // A reset, not a clean close: the client sees the line drop mid-answer.
                socket.setSoLinger(true, 0);
                return;
            }
            writeChunk(out, "data: [DONE]\n\n");
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException | InterruptedException ignored) {
            // The client went away; the test reads what arrived, and when the stub noticed.
            brokenAt.compareAndSet(null, Instant.now());
        }
    }

    private static void writeChunk(OutputStream out, String data) throws IOException {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
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
