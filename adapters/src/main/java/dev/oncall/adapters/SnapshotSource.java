package dev.oncall.adapters;

import dev.oncall.adapters.Telemetry.Alert;
import dev.oncall.adapters.Telemetry.DeployRecord;
import dev.oncall.adapters.Telemetry.LogEntry;
import dev.oncall.adapters.Telemetry.MetricSeries;
import dev.oncall.adapters.Telemetry.Point;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 캡처한 시험지(scenarios/snapshots/<id>/)를 읽는다.
 * 정보 경계: 아래 네 파일만 연다. meta.json(주입 시각, 정의 해시)은 평가 전용이라 절대 읽지 않는다.
 */
public final class SnapshotSource implements TelemetrySource {

    /** 읽어도 되는 파일. meta.json은 의도적으로 빠져 있다. */
    public static final List<String> ALLOWED_FILES = List.of("alert.json", "logs.jsonl", "metrics.json", "deploys.json");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Alert alert;
    private final TimeRange available;
    private final List<LogEntry> logs;
    private final List<MetricSeries> metrics;
    private final List<DeployRecord> deploys;

    public SnapshotSource(Path dir) {
        this.alert = readAlert(read(dir, "alert.json"));
        this.logs = readLogs(read(dir, "logs.jsonl"));
        JsonNode m = JSON.readTree(read(dir, "metrics.json"));
        this.available = new TimeRange(Instant.parse(m.path("start").asString()), Instant.parse(m.path("end").asString()));
        this.metrics = readMetrics(m);
        this.deploys = readDeploys(read(dir, "deploys.json"));
    }

    @Override
    public Alert alert() {
        return alert;
    }

    @Override
    public TimeRange available() {
        return available;
    }

    @Override
    public Set<String> services() {
        Set<String> s = new TreeSet<>();
        logs.forEach(l -> s.add(l.service()));
        return Collections.unmodifiableSet(s);
    }

    @Override
    public Set<String> metricNames() {
        Set<String> s = new TreeSet<>();
        metrics.forEach(m -> s.add(m.name()));
        return Collections.unmodifiableSet(s);
    }

    @Override
    public List<LogEntry> logs(String service, TimeRange range) {
        return logs.stream().filter(l -> l.service().equals(service) && range.contains(l.ts())).toList();
    }

    @Override
    public List<MetricSeries> metric(String name, TimeRange range) {
        List<MetricSeries> out = new ArrayList<>();
        for (MetricSeries s : metrics) {
            if (s.name().equals(name)) {
                out.add(new MetricSeries(s.name(), s.labels(),
                        s.points().stream().filter(p -> range.contains(p.ts())).toList()));
            }
        }
        return out;
    }

    @Override
    public List<DeployRecord> deploys(TimeRange range) {
        return deploys.stream().filter(d -> range.contains(d.deployedAt())).toList();
    }

    private static String read(Path dir, String name) {
        if (!ALLOWED_FILES.contains(name)) {
            throw new IllegalStateException("허용되지 않은 스냅샷 파일: " + name);
        }
        try {
            return Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("스냅샷 파일을 읽을 수 없음: " + name, e);
        }
    }

    private static Alert readAlert(String text) {
        JsonNode item = JSON.readTree(text).path("alerts").path(0);
        Map<String, String> labels = strings(item.path("labels"));
        return new Alert(labels.getOrDefault("alertname", ""), labels, strings(item.path("annotations")),
                Instant.parse(item.path("startsAt").asString()), text);
    }

    private static List<LogEntry> readLogs(String text) {
        List<LogEntry> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode n = JSON.readTree(line);
            out.add(new LogEntry(Instant.parse(n.path("ts").asString()), n.path("service").asString(),
                    n.path("line").asString()));
        }
        out.sort(Comparator.comparing(LogEntry::ts));
        return List.copyOf(out);
    }

    private static List<MetricSeries> readMetrics(JsonNode root) {
        List<MetricSeries> out = new ArrayList<>();
        for (JsonNode s : root.path("series")) {
            List<Point> points = new ArrayList<>();
            for (JsonNode p : s.path("points")) {
                points.add(new Point(Instant.ofEpochSecond(p.get(0).asLong()),
                        p.get(1).isNull() ? null : p.get(1).asDouble()));
            }
            out.add(new MetricSeries(s.path("name").asString(), strings(s.path("labels")), List.copyOf(points)));
        }
        return List.copyOf(out);
    }

    private static List<DeployRecord> readDeploys(String text) {
        List<DeployRecord> out = new ArrayList<>();
        for (JsonNode d : JSON.readTree(text)) {
            List<String> changes = new ArrayList<>();
            d.path("changes").forEach(c -> changes.add(c.asString()));
            out.add(new DeployRecord(d.path("id").asString(), d.path("service").asString(),
                    d.path("version").asString(),
                    d.path("previous_version").isNull() ? null : d.path("previous_version").asString(),
                    Instant.parse(d.path("deployed_at").asString()), d.path("author").asString(),
                    d.path("summary").asString(), List.copyOf(changes)));
        }
        out.sort(Comparator.comparing(DeployRecord::deployedAt));
        return List.copyOf(out);
    }

    private static Map<String, String> strings(JsonNode object) {
        Map<String, String> map = new LinkedHashMap<>();
        object.properties().forEach(e -> map.put(e.getKey(), e.getValue().asString()));
        return Collections.unmodifiableMap(map);
    }
}
