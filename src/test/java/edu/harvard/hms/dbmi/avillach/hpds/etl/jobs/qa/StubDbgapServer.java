package edu.harvard.hms.dbmi.avillach.hpds.etl.jobs.qa;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A local stand-in for the dbGaP SSTR API (no network, no new dependency): per accession
 * ("phs001412.v4") a queue of canned responses, served in order; the last one repeats. Records every
 * request path.
 */
final class StubDbgapServer implements AutoCloseable {

    /** One canned response; {@code delayMillis} holds it back (to provoke a client timeout). */
    record Reply(int status, String body, Map<String, String> headers, long delayMillis) {
        static Reply json(int status, String body) {
            return new Reply(status, body, Map.of("Content-Type", "application/json"), 0);
        }

        Reply withHeader(String name, String value) {
            Map<String, String> h = new java.util.LinkedHashMap<>(headers);
            h.put(name, value);
            return new Reply(status, body, h, delayMillis);
        }

        Reply delayed(long millis) {
            return new Reply(status, body, headers, millis);
        }
    }

    static final String NOT_FOUND_BODY_TEMPLATE =
            "{\"error\":{\"error_message\":\"Invalid or non-existent study with accession %s\",\"error_code\":2}}";

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Deque<Reply>> replies = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    StubDbgapServer() {
        this(0);
    }

    StubDbgapServer(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    /** The base URL to configure the client with, e.g. {@code http://127.0.0.1:1234/study}. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/study";
    }

    /** Queues replies for {@code accession}; the last one is repeated once the queue is drained. */
    StubDbgapServer on(String accession, Reply... queued) {
        replies.computeIfAbsent(accession, k -> new ArrayDeque<>()).addAll(List.of(queued));
        return this;
    }

    List<String> requests() {
        return List.copyOf(requests);
    }

    long requestsFor(String accession) {
        return requests.stream().filter(p -> p.equals("/study/" + accession + "/summary")).count();
    }

    static String sample() {
        try (InputStream in = StubDbgapServer.class.getResourceAsStream("/qa/phs001412.v4.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A minimal summary for {@code phs.vN} with the given (code, subject_count) pairs. */
    static String summary(String accession, int version, long... codeAndCount) {
        StringBuilder groups = new StringBuilder();
        for (int i = 0; i < codeAndCount.length; i += 2) {
            if (groups.length() > 0) {
                groups.append(',');
            }
            groups.append("{\"code\":").append(codeAndCount[i]).append(",\"short_name\":\"G")
                    .append(codeAndCount[i]).append("\",\"subject_count\":").append(codeAndCount[i + 1]).append('}');
        }
        return "{\"study\":{\"accver\":{\"accession\":\"" + accession + "\",\"version\":" + version
                + "},\"consent_groups\":[" + groups + "]}}";
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(path);
        String accession = path.replaceFirst("^/study/", "").replaceFirst("/summary$", "");
        Deque<Reply> queue = replies.get(accession);
        Reply reply;
        synchronized (this) {
            reply = queue == null ? null : (queue.size() > 1 ? queue.pollFirst() : queue.peekFirst());
        }
        if (reply == null) {
            reply = Reply.json(400, String.format(NOT_FOUND_BODY_TEMPLATE, accession));
        }
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (IOException e) {
            // The client gave up (a timeout test); nothing to answer.
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
