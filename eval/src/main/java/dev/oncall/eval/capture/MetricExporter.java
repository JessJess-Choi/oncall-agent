package dev.oncall.eval.capture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 허용 메트릭(capture/metrics.yaml)만 캡처 구간에 대해 조회해 metrics.json으로 만든다.
 * 임의 PromQL은 받지 않는다. 이 목록이 2주차 get_metrics 허용 목록의 출발점이다.
 */
final class MetricExporter {

    static final String RESOURCE = "/capture/metrics.yaml";
    private static final Set<String> DROPPED_LABELS = Set.of("__name__", "job", "instance");

    record MetricDef(String name, String query) {
    }

    private final Ports.Prometheus prometheus;
    private final List<MetricDef> defs;
    private final Duration step;

    MetricExporter(Ports.Prometheus prometheus, List<MetricDef> defs, Duration step) {
        this.prometheus = prometheus;
        this.defs = defs;
        this.step = step;
    }

    static List<MetricDef> loadDefs() throws IOException {
        try (InputStream in = MetricExporter.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("리소스 없음: " + RESOURCE);
            }
            JsonNode tree = Json.YAML.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            List<MetricDef> defs = new ArrayList<>();
            tree.path("metrics").forEach(m -> defs.add(new MetricDef(m.get("name").asString(),
                    m.get("query").asString().trim())));
            return defs;
        }
    }

    ObjectNode export(Instant start, Instant end) {
        record Series(String name, String labelKey, ObjectNode node) {
        }
        List<Series> all = new ArrayList<>();
        for (MetricDef def : defs) {
            for (JsonNode result : prometheus.queryRange(def.query(), start, end, step)) {
                Map<String, String> labels = new TreeMap<>();
                result.path("metric").properties().forEach(e -> {
                    if (!DROPPED_LABELS.contains(e.getKey())) {
                        labels.put(e.getKey(), e.getValue().asString());
                    }
                });
                ObjectNode series = Json.object();
                series.put("name", def.name());
                ObjectNode labelNode = series.putObject("labels");
                labels.forEach(labelNode::put);
                ArrayNode points = series.putArray("points");
                for (JsonNode v : result.path("values")) {
                    ArrayNode point = points.addArray();
                    point.add(Math.round(v.get(0).asDouble()));
                    Double value = number(v.get(1).asString());
                    if (value == null) {
                        point.addNull();
                    } else {
                        point.add(value);
                    }
                }
                all.add(new Series(def.name(), labels.toString(), series));
            }
        }
        all.sort(Comparator.comparing(Series::name).thenComparing(Series::labelKey));

        ObjectNode root = Json.object();
        root.put("step_seconds", step.toSeconds());
        root.put("start", start.toString());
        root.put("end", end.toString());
        ArrayNode series = root.putArray("series");
        all.forEach(s -> series.add(s.node()));
        return root;
    }

    /** Prometheus의 NaN·Inf는 값이 없는 것으로 본다 (예: 요청이 없는 구간의 p95). */
    static Double number(String text) {
        double d = Double.parseDouble(text);
        return Double.isFinite(d) ? d : null;
    }
}
