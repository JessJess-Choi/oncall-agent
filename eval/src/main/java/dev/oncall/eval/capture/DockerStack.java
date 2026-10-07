package dev.oncall.eval.capture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** docker compose와 toxiproxy API로 대상 시스템을 조작한다. */
final class DockerStack implements Ports.Stack {

    static final String COMPOSE_FILE = "infra/docker-compose.yml";
    static final Set<String> REQUIRED = Set.of("postgres", "order-api", "payment-api", "toxiproxy", "loadgen",
            "prometheus", "loki", "alloy");
    static final String INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_orders_customer_created ON orders (customer_id, created_at DESC)";

    private static final Map<String, String> VERSION_ENV =
            Map.of("order-api", "ORDER_API_VERSION", "payment-api", "PAYMENT_API_VERSION");

    private final Path root;
    private final String toxiproxyUrl;
    private final String orderApiUrl;
    private final Map<String, String> current = new TreeMap<>(Versions.BASELINE);

    DockerStack(Path root, String toxiproxyUrl, String orderApiUrl) {
        this.root = root;
        this.toxiproxyUrl = toxiproxyUrl;
        this.orderApiUrl = orderApiUrl;
    }

    @Override
    public void checkReady() {
        String out = run(compose("ps", "--status", "running", "--services"), Map.of(), Duration.ofSeconds(30));
        List<String> missing = new ArrayList<>();
        for (String service : REQUIRED) {
            if (!out.lines().map(String::trim).toList().contains(service)) {
                missing.add(service);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("실행 중이 아닌 서비스: " + missing + " (make up 먼저)");
        }
        HttpClients.get(toxiproxyUrl, "/proxies", Map.of());
    }

    @Override
    public void reset() {
        Map<String, String> env = new LinkedHashMap<>();
        Versions.BASELINE.forEach((service, version) -> env.put(VERSION_ENV.get(service), version));
        run(compose("up", "-d", "order-api", "payment-api"), env, Duration.ofMinutes(3));
        current.clear();
        current.putAll(Versions.BASELINE);
        sql(INDEX_SQL);
        for (JsonNode toxic : Json.parse(HttpClients.get(toxiproxyUrl, "/proxies/payment/toxics", Map.of()))) {
            HttpClients.send(HttpRequest.newBuilder(HttpClients.uri(toxiproxyUrl,
                    "/proxies/payment/toxics/" + toxic.get("name").asString(), Map.of())).DELETE());
        }
    }

    @Override
    public boolean orderApiHealthy() {
        try {
            return HttpClients.get(orderApiUrl, "/actuator/health", Map.of()).contains("\"UP\"");
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void deploy(String service, String version) {
        Map<String, String> env = new LinkedHashMap<>();
        current.forEach((s, v) -> env.put(VERSION_ENV.get(s), v));
        env.put(VERSION_ENV.get(service), version);
        run(compose("up", "-d", service), env, Duration.ofMinutes(3));
        current.put(service, version);
    }

    @Override
    public void sql(String statement) {
        run(compose("exec", "-T", "postgres", "psql", "-U", "app", "-d", "shop", "-v", "ON_ERROR_STOP=1",
                "-c", statement), Map.of(), Duration.ofMinutes(5));
    }

    @Override
    public void addToxic(Step.Toxic toxic) {
        ObjectNode body = Json.object();
        body.put("name", toxic.type() + "_" + toxic.stream());
        body.put("type", toxic.type());
        body.put("stream", toxic.stream());
        body.put("toxicity", 1.0);
        body.set("attributes", toxic.attributes());
        HttpClients.send(HttpRequest.newBuilder(HttpClients.uri(toxiproxyUrl,
                        "/proxies/" + toxic.proxy() + "/toxics", Map.of()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.compact(body))));
    }

    @Override
    public Map<String, String> currentVersions() {
        return Map.copyOf(current);
    }

    @Override
    public String gitDescribe() {
        String commit = run(List.of("git", "rev-parse", "HEAD"), Map.of(), Duration.ofSeconds(30)).trim();
        boolean dirty = !run(List.of("git", "status", "--porcelain"), Map.of(), Duration.ofSeconds(30)).isBlank();
        return commit + (dirty ? "-dirty" : "");
    }

    private List<String> compose(String... args) {
        List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-f", COMPOSE_FILE));
        cmd.addAll(List.of(args));
        return cmd;
    }

    private String run(List<String> cmd, Map<String, String> env, Duration timeout) {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true);
        pb.environment().putAll(env);
        try {
            Process p = pb.start();
            byte[] out;
            try (InputStream in = p.getInputStream()) {
                out = in.readAllBytes();
            }
            if (!p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("시간 초과: " + String.join(" ", cmd));
            }
            String text = new String(out, StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                throw new IllegalStateException("명령 실패(" + p.exitValue() + "): " + String.join(" ", cmd) + "\n"
                        + text);
            }
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("명령 실행 불가: " + String.join(" ", cmd), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("명령 중단: " + String.join(" ", cmd), e);
        }
    }
}
