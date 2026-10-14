package dev.oncall.tools;

import dev.oncall.adapters.Telemetry.Alert;
import dev.oncall.adapters.Telemetry.DeployRecord;
import dev.oncall.adapters.Telemetry.LogEntry;
import dev.oncall.adapters.Telemetry.MetricSeries;
import dev.oncall.adapters.Telemetry.Point;
import dev.oncall.adapters.TelemetrySource;
import dev.oncall.adapters.TimeRange;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 파일 없이 메모리에서 쓰는 데이터 소스. 툴 로직 테스트용. */
final class FakeSource implements TelemetrySource {

    static final Instant START = Instant.parse("2026-10-07T12:00:00Z");
    static final Instant END = Instant.parse("2026-10-07T12:10:00Z");

    final List<LogEntry> logs = new ArrayList<>();
    final List<MetricSeries> metrics = new ArrayList<>();
    final List<DeployRecord> deploys = new ArrayList<>();

    static Instant at(String hhmmss) {
        return Instant.parse("2026-10-07T" + hhmmss + "Z");
    }

    /** 앱(logback JSON) 형식 로그 한 줄 */
    static String appLine(String level, String message, String stackTrace) {
        String st = stackTrace == null ? "" : ",\"stack_trace\":\"" + stackTrace.replace("\n", "\\n").replace("\t", "\\t") + "\"";
        return "{\"@timestamp\":\"x\",\"message\":\"" + message + "\",\"logger_name\":\"dev.oncall.sample.order.OrderService\","
                + "\"level\":\"" + level + "\"" + st + "}";
    }

    FakeSource log(String hhmmss, String service, String line) {
        logs.add(new LogEntry(at(hhmmss), service, line));
        return this;
    }

    FakeSource series(String name, Map<String, String> labels, Double... values) {
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            pts.add(new Point(START.plusSeconds(15L * i), values[i]));
        }
        metrics.add(new MetricSeries(name, labels, pts));
        return this;
    }

    FakeSource deploy(String id, String service, Instant at) {
        deploys.add(new DeployRecord(id, service, "1.1.0", "1.0.0", at, "bot", "요약 " + id, List.of("상세 변경 " + id)));
        return this;
    }

    @Override
    public Alert alert() {
        return new Alert("HighErrorRate", Map.of(), Map.of(), at("12:08:00"), "{}");
    }

    @Override
    public TimeRange available() {
        return new TimeRange(START, END);
    }

    @Override
    public Set<String> services() {
        Set<String> s = new TreeSet<>(Set.of("order-api", "payment-api", "postgres"));
        logs.forEach(l -> s.add(l.service()));
        return s;
    }

    @Override
    public Set<String> metricNames() {
        Set<String> s = new TreeSet<>();
        metrics.forEach(m -> s.add(m.name()));
        return s;
    }

    @Override
    public List<LogEntry> logs(String service, TimeRange range) {
        return logs.stream().filter(l -> l.service().equals(service) && range.contains(l.ts())).toList();
    }

    @Override
    public List<MetricSeries> metric(String name, TimeRange range) {
        return metrics.stream().filter(m -> m.name().equals(name))
                .map(m -> new MetricSeries(m.name(), m.labels(),
                        m.points().stream().filter(p -> range.contains(p.ts())).toList()))
                .toList();
    }

    @Override
    public List<DeployRecord> deploys(TimeRange range) {
        return deploys.stream().filter(d -> range.contains(d.deployedAt())).toList();
    }
}
