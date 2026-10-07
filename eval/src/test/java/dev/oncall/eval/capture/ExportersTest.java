package dev.oncall.eval.capture;

import dev.oncall.eval.capture.ScenarioDefinition.AlertSpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportersTest {

    private static final Instant T0 = Instant.parse("2026-10-07T01:00:00Z");

    @Test
    void 배포_ID는_결정적이고_범위_안이다() {
        String a = DeployIds.of("pool-exhaustion-01", "fault/0");
        assertEquals(a, DeployIds.of("pool-exhaustion-01", "fault/0"));
        assertTrue(a.matches("deploy-[1-9][0-9]{3}"), a);
        assertFalse(a.equals(DeployIds.of("pool-exhaustion-01", "fault/1")));
    }

    @Test
    void Prometheus_알림을_Alertmanager_웹훅으로_바꾸고_내부_링크는_뺀다() {
        JsonNode alerts = Json.parse("""
                [{"labels":{"alertname":"HighErrorRate","service":"order-api","severity":"page"},
                  "annotations":{"summary":"order-api 5xx 비율이 5%를 넘었다","value":"23%"},
                  "state":"firing","activeAt":"2026-10-07T01:03:10Z","value":"0.23",
                  "generatorURL":"http://prometheus:9090/graph?g0.expr=..."},
                 {"labels":{"alertname":"HighLatency","service":"order-api","severity":"page"},"state":"firing"}]
                """);
        AlertSpec spec = new AlertSpec("HighErrorRate", Map.of("service", "order-api"), Duration.ofMinutes(8));

        JsonNode alert = AlertPayloads.findFiring(alerts, spec).orElseThrow();
        ObjectNode payload = AlertPayloads.toWebhook(alert, T0);

        assertEquals("4", payload.get("version").asString());
        assertEquals("HighErrorRate", payload.get("commonLabels").get("alertname").asString());
        assertEquals(T0.toString(), payload.get("alerts").get(0).get("startsAt").asString());
        assertFalse(Json.compact(payload).contains("generatorURL"));
        assertFalse(Json.compact(payload).contains("prometheus:9090"));
    }

    @Test
    void pending_알림은_찾지_않는다() {
        JsonNode alerts = Json.parse("""
                [{"labels":{"alertname":"HighErrorRate","service":"order-api"},"state":"pending"}]
                """);
        AlertSpec spec = new AlertSpec("HighErrorRate", Map.of("service", "order-api"), Duration.ofMinutes(8));
        assertTrue(AlertPayloads.findFiring(alerts, spec).isEmpty());
        assertTrue(AlertPayloads.anyFor(alerts, "order-api"));
    }

    @Test
    void 로그를_서비스별로_모아_시간순으로_정렬하고_중복을_없앤다() {
        Ports.Loki loki = (q, s, e, limit) -> {
            if (q.contains("order-api")) {
                return Json.parse("[{\"stream\":{},\"values\":[[\"" + nanos(T0.plusSeconds(2)) + "\",\"B\"],"
                        + "[\"" + nanos(T0.plusSeconds(2)) + "\",\"B\"]]}]");
            }
            if (q.contains("postgres")) {
                return Json.parse("[{\"stream\":{},\"values\":[[\"" + nanos(T0.plusSeconds(1)) + "\",\"A\"]]}]");
            }
            return Json.array();
        };
        List<LogExporter.LogLine> lines = new LogExporter(loki, Duration.ofMinutes(10), 100).export(T0,
                T0.plusSeconds(60));

        assertEquals(List.of("A", "B"), lines.stream().map(LogExporter.LogLine::line).toList());
        String jsonl = LogExporter.toJsonl(lines);
        assertTrue(jsonl.startsWith("{\"ts\":\"2026-10-07T01:00:01Z\",\"service\":\"postgres\",\"line\":\"A\"}\n"),
                jsonl);
    }

    @Test
    void 로그를_구간_단위로_나눠_조회한다() {
        AtomicInteger calls = new AtomicInteger();
        Ports.Loki loki = (q, s, e, limit) -> {
            calls.incrementAndGet();
            return Json.array();
        };
        new LogExporter(loki, Duration.ofSeconds(60), 100).export(T0, T0.plusSeconds(150));
        assertEquals(3 * LogExporter.SERVICES.size(), calls.get());
    }

    @Test
    void Loki_결과가_limit에_닿으면_잘린_것으로_보고_실패한다() {
        Ports.Loki loki = (q, s, e, limit) -> Json.parse("[{\"stream\":{},\"values\":[[\"" + nanos(T0)
                + "\",\"x\"],[\"" + nanos(T0.plusSeconds(1)) + "\",\"y\"]]}]");
        assertThrows(IllegalStateException.class,
                () -> new LogExporter(loki, Duration.ofMinutes(1), 2).export(T0, T0.plusSeconds(30)));
    }

    @Test
    void 메트릭은_허용_목록만_조회하고_NaN은_null로_바꾸며_job_instance_라벨은_뺀다() {
        Ports.Prometheus prometheus = new Ports.Prometheus() {
            public Instant now() {
                return T0;
            }

            public JsonNode alerts() {
                return Json.array();
            }

            public JsonNode queryRange(String query, Instant start, Instant end, Duration step) {
                return Json.parse("""
                        [{"metric":{"service":"order-api","job":"order-api","instance":"order-api:8080"},
                          "values":[[1791334800,"0.5"],[1791334815,"NaN"]]}]
                        """);
            }
        };
        ObjectNode out = new MetricExporter(prometheus,
                List.of(new MetricExporter.MetricDef("http_latency_p95", "q")), Duration.ofSeconds(15))
                .export(T0, T0.plusSeconds(30));

        JsonNode series = out.get("series").get(0);
        assertEquals("http_latency_p95", series.get("name").asString());
        assertEquals("{\"service\":\"order-api\"}", Json.compact(series.get("labels")));
        assertEquals("[[1791334800,0.5],[1791334815,null]]", Json.compact(series.get("points")));
    }

    @Test
    void 허용_메트릭_목록_리소스를_읽는다() throws Exception {
        List<MetricExporter.MetricDef> defs = MetricExporter.loadDefs();
        assertTrue(defs.size() >= 10);
        assertTrue(defs.stream().noneMatch(d -> d.query().contains("client_name")),
                "toxiproxy 호스트 이름이 담긴 라벨은 쓰지 않는다");
    }

    @Test
    void 배포_이력은_과거_기준_배포와_이번_배포를_시간순으로_합친다() {
        Versions versions = TestSupport.versions();
        List<DeployHistory.Deploy> deploys = new java.util.ArrayList<>(DeployHistory.past("bad-deploy-01", T0));
        deploys.add(new DeployHistory.Deploy("deploy-1234", "order-api", "1.2.0", "1.0.0", T0.plusSeconds(180)));

        JsonNode json = DeployHistory.toJson(deploys, versions);

        assertEquals(3, json.size());
        assertEquals("payment-api", json.get(0).get("service").asString());
        assertTrue(json.get(0).get("previous_version").isNull());
        assertEquals("1.2.0", json.get(2).get("version").asString());
        assertEquals("쿠폰 코드 정규화 로직 공통화", json.get(2).get("summary").asString());
    }

    private static String nanos(Instant t) {
        return t.getEpochSecond() + String.format("%09d", t.getNano());
    }
}
