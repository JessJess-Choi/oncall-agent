package dev.oncall.tools;

import dev.oncall.adapters.Telemetry.DeployRecord;
import dev.oncall.adapters.TelemetrySource;
import dev.oncall.adapters.TimeRange;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * get_recent_deploys: 최근 배포 이력. 시작 시각을 주지 않으면 조회 가능 구간의 끝에서 며칠 전까지 본다
 * (배포는 장애보다 훨씬 앞설 수 있다). 변경 상세(changes)는 get_diff(이후 주차)로 분리한다.
 */
final class DeployQuery {

    static final Set<String> KEYS = Set.of("service", "start", "end");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TelemetrySource source;
    private final Limits limits;

    DeployQuery(TelemetrySource source, Limits limits) {
        this.source = source;
        this.limits = limits;
    }

    ObjectNode run(Args args) {
        args.onlyKeys(KEYS);
        String service = args.optionalString("service");
        Instant end = source.available().end();
        // 앞쪽은 조회 가능 구간보다 넓게 허용하되, 뒤쪽(미래)은 넘지 못하게 한다.
        TimeRange range = args.range(end.minus(Duration.ofDays(limits.deployLookbackDays())), end,
                new TimeRange(Instant.EPOCH, end));

        List<DeployRecord> deploys = source.deploys(range).stream()
                .filter(d -> service == null || service.equals(d.service()))
                .toList();
        List<DeployRecord> shown = deploys.subList(Math.max(0, deploys.size() - limits.deployMax()), deploys.size());

        ObjectNode out = JSON.createObjectNode();
        out.put("tool", "get_recent_deploys");
        out.put("service", service);
        ObjectNode r = out.putObject("range");
        r.put("start", range.start().toString());
        r.put("end", range.end().toString());
        out.put("total", deploys.size());
        ArrayNode arr = out.putArray("deploys");
        for (DeployRecord d : shown) {
            ObjectNode o = arr.addObject();
            o.put("id", d.id());
            o.put("service", d.service());
            o.put("version", d.version());
            o.put("previous_version", d.previousVersion());
            o.put("deployed_at", d.deployedAt().toString());
            o.put("author", d.author());
            o.put("summary", d.summary());
        }
        return out;
    }
}
