package dev.oncall.eval.capture;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Prometheus·Loki HTTP API 구현과 공용 요청 도구. */
final class HttpClients {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpClients() {
    }

    /** 조회 실패(연결·시간 초과)만 재시도한다. HTTP 오류 응답은 재시도해도 같으므로 바로 실패한다. */
    static final int GET_ATTEMPTS = 3;
    static final Duration GET_BACKOFF = Duration.ofSeconds(5);

    /**
     * 조회(GET)는 읽기 전용이라 안전하게 재시도할 수 있다.
     * 부하가 큰 장애 구간(예: slow_query)에서는 Loki 조회가 느려져 한 번에 응답하지 않을 수 있다.
     */
    static String get(String base, String path, Map<String, String> params) {
        return withRetry(GET_ATTEMPTS, () -> send(HttpRequest.newBuilder(uri(base, path, params)).GET()),
                HttpClients::pause);
    }

    static <T> T withRetry(int attempts, Supplier<T> call, Consumer<Duration> sleeper) {
        for (int i = 1; ; i++) {
            try {
                return call.get();
            } catch (UncheckedIOException e) {
                if (i >= attempts) {
                    throw new IllegalStateException("요청 실패 (" + attempts + "회 시도): " + e.getCause().getMessage(), e);
                }
                sleeper.accept(GET_BACKOFF.multipliedBy(i));
            }
        }
    }

    static String send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> res = HTTP.send(request.timeout(Duration.ofMinutes(3)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException(res.request().method() + " " + res.uri() + " → " + res.statusCode()
                        + ": " + res.body());
            }
            return res.body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("요청 중단", e);
        }
    }

    private static void pause(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("요청 중단", e);
        }
    }

    static URI uri(String base, String path, Map<String, String> params) {
        String query = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return URI.create(base + path + (query.isEmpty() ? "" : "?" + query));
    }

    private static JsonNode data(String body) {
        JsonNode root = Json.parse(body);
        if (!"success".equals(root.path("status").asString())) {
            throw new IllegalStateException("API 오류: " + body);
        }
        return root.get("data");
    }

    static final class HttpPrometheus implements Ports.Prometheus {
        private final String base;

        HttpPrometheus(String base) {
            this.base = base;
        }

        @Override
        public Instant now() {
            JsonNode result = data(get(base, "/api/v1/query", Map.of("query", "time()"))).get("result");
            double seconds = Double.parseDouble(result.get(1).asString());
            return Instant.ofEpochMilli(Math.round(seconds * 1000));
        }

        @Override
        public JsonNode alerts() {
            return data(get(base, "/api/v1/alerts", Map.of())).get("alerts");
        }

        @Override
        public JsonNode queryRange(String query, Instant start, Instant end, Duration step) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("query", query);
            params.put("start", Long.toString(start.getEpochSecond()));
            params.put("end", Long.toString(end.getEpochSecond()));
            params.put("step", Long.toString(step.toSeconds()));
            return data(get(base, "/api/v1/query_range", params)).get("result");
        }
    }

    static final class HttpLoki implements Ports.Loki {
        private final String base;

        HttpLoki(String base) {
            this.base = base;
        }

        @Override
        public JsonNode queryRange(String logql, Instant start, Instant end, int limit) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("query", logql);
            params.put("start", nanos(start));
            params.put("end", nanos(end));
            params.put("limit", Integer.toString(limit));
            params.put("direction", "forward");
            return data(get(base, "/loki/api/v1/query_range", params)).get("result");
        }

        private static String nanos(Instant t) {
            return t.getEpochSecond() + String.format("%09d", t.getNano());
        }
    }
}
