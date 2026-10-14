package dev.oncall.tools;

import dev.oncall.adapters.Telemetry.MetricSeries;
import dev.oncall.adapters.Telemetry.Point;
import dev.oncall.adapters.TelemetrySource;
import dev.oncall.adapters.TimeRange;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * get_metrics: 허용된 메트릭 이름으로만 조회한다(임의 쿼리 금지). 계열마다 요약과, 일정 간격으로 솎은 점을 돌려준다.
 */
final class MetricQuery {

    static final Set<String> KEYS = Set.of("name", "service", "start", "end");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TelemetrySource source;
    private final Limits limits;

    MetricQuery(TelemetrySource source, Limits limits) {
        this.source = source;
        this.limits = limits;
    }

    ObjectNode run(Args args) {
        args.onlyKeys(KEYS);
        String name = Args.oneOf("name", args.requiredString("name"), source.metricNames());
        String service = args.optionalString("service");
        TimeRange range = args.range(source.available().start(), source.available().end(), source.available());

        List<MetricSeries> series = new ArrayList<>();
        for (MetricSeries s : source.metric(name, range)) {
            if (service == null || service.equals(s.labels().get("service"))) {
                series.add(s);
            }
        }

        ObjectNode out = JSON.createObjectNode();
        out.put("tool", "get_metrics");
        out.put("name", name);
        out.put("service", service);
        ObjectNode r = out.putObject("range");
        r.put("start", range.start().toString());
        r.put("end", range.end().toString());
        out.put("series_total", series.size());
        ArrayNode arr = out.putArray("series");
        for (MetricSeries s : series.subList(0, Math.min(series.size(), limits.metricMaxSeries()))) {
            ObjectNode o = arr.addObject();
            ObjectNode labels = o.putObject("labels");
            s.labels().forEach(labels::put);
            List<Double> values = s.points().stream().map(Point::value).filter(Objects::nonNull).toList();
            ObjectNode sum = o.putObject("summary");
            sum.put("points_total", s.points().size());
            if (!values.isEmpty()) {
                sum.put("min", values.stream().mapToDouble(Double::doubleValue).min().orElseThrow());
                sum.put("max", values.stream().mapToDouble(Double::doubleValue).max().orElseThrow());
                sum.put("first", values.getFirst());
                sum.put("last", values.getLast());
            }
            ArrayNode pts = o.putArray("points");
            for (Point p : thin(s.points(), limits.metricMaxPoints())) {
                ArrayNode pa = pts.addArray();
                pa.add(p.ts().toString());
                if (p.value() == null) {
                    pa.addNull();
                } else {
                    pa.add(p.value());
                }
            }
        }
        if (series.size() > arr.size()) {
            out.put("note", "계열 " + series.size() + "개 중 " + arr.size() + "개만 보여 준다. service로 좁힐 수 있다.");
        }
        return out;
    }

    /** 점이 많으면 일정 간격으로 솎는다. 마지막 점은 항상 남긴다. */
    static List<Point> thin(List<Point> points, int max) {
        if (points.size() <= max) {
            return points;
        }
        int stride = (int) Math.ceil(points.size() / (double) max);
        List<Point> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i += stride) {
            out.add(points.get(i));
        }
        if (!out.getLast().equals(points.getLast())) {
            out.add(points.getLast());
        }
        return out;
    }
}
