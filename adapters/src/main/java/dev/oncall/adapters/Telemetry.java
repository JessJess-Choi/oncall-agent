package dev.oncall.adapters;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 데이터 소스가 돌려주는 값들. Jackson 같은 외부 타입을 노출하지 않는다. */
public final class Telemetry {

    private Telemetry() {
    }

    /** 알림 한 건. raw는 알림 원문(Alertmanager 웹훅 형식). */
    public record Alert(String name, Map<String, String> labels, Map<String, String> annotations, Instant startsAt,
                        String raw) {
    }

    /** 로그 한 줄. line은 원문 그대로 (앱은 JSON 문자열, Postgres는 일반 텍스트). */
    public record LogEntry(Instant ts, String service, String line) {
    }

    public record Point(Instant ts, Double value) {
    }

    public record MetricSeries(String name, Map<String, String> labels, List<Point> points) {
    }

    public record DeployRecord(String id, String service, String version, String previousVersion, Instant deployedAt,
                               String author, String summary, List<String> changes) {
    }
}
