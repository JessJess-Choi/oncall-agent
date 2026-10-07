package dev.oncall.eval.capture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 가짜 스택·시계로 캡처 흐름을 검증한다. 실제 시간은 흐르지 않는다. */
class CaptureRunnerTest {

    private static final Instant T0 = Instant.parse("2026-10-07T01:00:00Z");

    @TempDir
    Path root;

    private FakeWorld world;
    private String logLine;

    @BeforeEach
    void setUp() {
        world = new FakeWorld();
        logLine = "{\"level\":\"ERROR\",\"message\":\"Unhandled exception on POST /orders\"}";
    }

    private ScenarioDefinition definition() {
        return new DefinitionLoader(TestSupport.versions(), Duration.ofMinutes(3)).parse("""
                id: bad-deploy-01
                service: order-api
                fault:
                  type: bad_deploy
                  steps:
                    - deploy: { service: order-api, version: "1.2.0" }
                background:
                  - after: 30s
                    deploy: { service: payment-api, version: "2.0.1" }
                alert:
                  name: HighErrorRate
                  labels: { service: order-api }
                  timeout: 8m
                """, "bad-deploy-01", "abc");
    }

    private CaptureRunner runner() {
        Ports.Loki loki = (q, s, e, limit) -> q.contains("order-api")
                ? Json.parse("[{\"stream\":{},\"values\":[[\"" + (s.getEpochSecond() * 1_000_000_000L) + "\","
                + Json.compact(Json.JSON.getNodeFactory().stringNode(logLine)) + "]]}]")
                : Json.array();
        return new CaptureRunner(world, world, new LogExporter(loki, Duration.ofMinutes(1), 1000),
                new MetricExporter(world, List.of(new MetricExporter.MetricDef("up", "up")), Duration.ofSeconds(15)),
                world, new SnapshotWriter(root), TestSupport.versions(),
                new CaptureRunner.Settings(Duration.ofMinutes(3), Duration.ofSeconds(60), Duration.ofMinutes(5),
                        Duration.ofMinutes(1), Duration.ofSeconds(10), Duration.ofSeconds(5)),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    @Test
    void 정상_흐름이면_스냅샷_5개_파일을_만들고_마지막에_기준선을_복원한다() throws Exception {
        world.fireAfterInject = Duration.ofSeconds(95);

        Path snapshot = runner().run(definition());

        for (String f : List.of("alert.json", "logs.jsonl", "metrics.json", "deploys.json", "meta.json")) {
            assertTrue(Files.isRegularFile(snapshot.resolve(f)), f);
        }
        assertEquals("checkReady", world.calls.get(0));
        assertEquals("reset", world.calls.get(1));
        assertEquals("reset", world.calls.get(world.calls.size() - 1));
        assertTrue(world.calls.indexOf("deploy payment-api 2.0.1") < world.calls.indexOf("deploy order-api 1.2.0"));

        JsonNode deploys = Json.parse(Files.readString(snapshot.resolve("deploys.json")));
        JsonNode last = deploys.get(deploys.size() - 1);
        assertEquals("1.2.0", last.get("version").asString());
        assertEquals("1.0.0", last.get("previous_version").asString());
        assertEquals(DeployIds.of("bad-deploy-01", "fault/0"), last.get("id").asString());

        JsonNode meta = Json.parse(Files.readString(snapshot.resolve("meta.json")));
        Instant start = Instant.parse(meta.get("window").get("start").asString());
        Instant injected = Instant.parse(meta.get("window").get("injected_at").asString());
        Instant fired = Instant.parse(meta.get("window").get("alert_fired_at").asString());
        assertEquals(Duration.ofMinutes(3), Duration.between(start, injected));
        assertTrue(Duration.between(injected, fired).compareTo(Duration.ofSeconds(95)) >= 0);
        assertEquals(Instant.parse(meta.get("window").get("end").asString()), fired.plus(Duration.ofMinutes(1)));
    }

    @Test
    void 알림이_제한_시간_안에_울리지_않으면_스냅샷을_만들지_않고_복원한다() {
        world.fireAfterInject = null;

        assertThrows(IllegalStateException.class, () -> runner().run(definition()));

        assertFalse(Files.exists(root.resolve("scenarios/snapshots/bad-deploy-01")));
        assertEquals("reset", world.calls.get(world.calls.size() - 1));
    }

    @Test
    void 로그에_주입_흔적이_있으면_스냅샷을_만들지_않는다() {
        world.fireAfterInject = Duration.ofSeconds(60);
        logLine = "I/O error on POST request for \"http://toxiproxy:18081/payments\"";

        assertThrows(IllegalStateException.class, () -> runner().run(definition()));
        assertFalse(Files.exists(root.resolve("scenarios/snapshots/bad-deploy-01")));
    }

    @Test
    void 스냅샷이_이미_있으면_스택을_건드리기_전에_거부한다() throws Exception {
        Files.createDirectories(root.resolve("scenarios/snapshots/bad-deploy-01"));

        assertThrows(IllegalStateException.class, () -> runner().run(definition()));
        assertTrue(world.calls.isEmpty());
    }

    /** 스택·Prometheus·시계를 한꺼번에 흉내 낸다. sleep하면 가짜 시각이 흐른다. */
    static final class FakeWorld implements Ports.Stack, Ports.Prometheus, Ports.Pacer {
        final List<String> calls = new ArrayList<>();
        final Map<String, String> versions = new TreeMap<>(Versions.BASELINE);
        Instant now = T0;
        Instant injectedAt;
        Duration fireAfterInject;

        @Override
        public void checkReady() {
            calls.add("checkReady");
        }

        @Override
        public void reset() {
            calls.add("reset");
            versions.putAll(Versions.BASELINE);
        }

        @Override
        public boolean orderApiHealthy() {
            return true;
        }

        @Override
        public void deploy(String service, String version) {
            calls.add("deploy " + service + " " + version);
            versions.put(service, version);
            if (service.equals("order-api")) {
                injectedAt = now;
            }
        }

        @Override
        public void sql(String statement) {
            calls.add("sql");
        }

        @Override
        public void addToxic(Step.Toxic toxic) {
            calls.add("toxic");
        }

        @Override
        public Map<String, String> currentVersions() {
            return Map.copyOf(versions);
        }

        @Override
        public String gitDescribe() {
            return "abc123";
        }

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public JsonNode alerts() {
            boolean firing = injectedAt != null && fireAfterInject != null
                    && !now.isBefore(injectedAt.plus(fireAfterInject));
            return firing
                    ? Json.parse("[{\"labels\":{\"alertname\":\"HighErrorRate\",\"service\":\"order-api\"},"
                    + "\"annotations\":{\"summary\":\"s\"},\"state\":\"firing\"}]")
                    : Json.array();
        }

        @Override
        public JsonNode queryRange(String query, Instant start, Instant end, Duration step) {
            return Json.parse("[{\"metric\":{\"service\":\"order-api\"},\"values\":[[" + start.getEpochSecond()
                    + ",\"1\"]]}]");
        }

        @Override
        public void sleep(Duration duration) {
            now = now.plus(duration);
        }
    }
}
