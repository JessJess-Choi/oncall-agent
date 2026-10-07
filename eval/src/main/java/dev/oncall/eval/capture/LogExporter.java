package dev.oncall.eval.capture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Loki에서 캡처 구간의 로그를 빠짐없이 가져와 logs.jsonl로 만든다.
 * 원문(line)은 가공하지 않는다. 앱의 JSON 로그도 문자열 그대로 둔다.
 */
final class LogExporter {

    /** Alloy가 수집하는 조사 대상과 같다 (infra/alloy/config.alloy). */
    static final List<String> SERVICES = List.of("order-api", "payment-api", "postgres");

    record LogLine(long epochNanos, String service, String line) {
    }

    private static final Comparator<LogLine> ORDER = Comparator.comparingLong(LogLine::epochNanos)
            .thenComparing(LogLine::service)
            .thenComparing(LogLine::line);

    private final Ports.Loki loki;
    private final Duration chunk;
    private final int limit;

    LogExporter(Ports.Loki loki, Duration chunk, int limit) {
        this.loki = loki;
        this.chunk = chunk;
        this.limit = limit;
    }

    List<LogLine> export(Instant start, Instant end) {
        TreeSet<LogLine> lines = new TreeSet<>(ORDER);
        for (String service : SERVICES) {
            for (Instant from = start; from.isBefore(end); from = from.plus(chunk)) {
                Instant to = from.plus(chunk).isAfter(end) ? end : from.plus(chunk);
                int count = 0;
                for (JsonNode stream : loki.queryRange("{service=\"" + service + "\"}", from, to, limit)) {
                    for (JsonNode value : stream.path("values")) {
                        lines.add(new LogLine(Long.parseLong(value.get(0).asString()), service,
                                value.get(1).asString()));
                        count++;
                    }
                }
                if (count >= limit) {
                    // 잘린 로그로 시험지를 만들면 안 된다. 구간을 줄이거나 limit을 올려야 한다.
                    throw new IllegalStateException("Loki 결과가 limit(" + limit + ")에 닿음: " + service + " "
                            + from + "~" + to);
                }
            }
        }
        return List.copyOf(lines);
    }

    static String toJsonl(List<LogLine> lines) {
        StringBuilder sb = new StringBuilder();
        for (LogLine l : lines) {
            ObjectNode node = Json.object();
            node.put("ts", Instant.ofEpochSecond(0, l.epochNanos()).toString());
            node.put("service", l.service());
            node.put("line", l.line());
            sb.append(Json.compact(node)).append('\n');
        }
        return sb.toString();
    }
}
