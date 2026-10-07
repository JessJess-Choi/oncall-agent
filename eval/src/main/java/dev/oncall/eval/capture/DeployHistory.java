package dev.oncall.eval.capture;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * deploys.json을 만든다. 기준 버전이 배포된 오래된 이력(합성, 기준선 시작 B 기준 상대 시각)과
 * 이번 캡처에서 실제로 실행한 deploy 단계를 합친다. 기준선 복원은 배포가 아니므로 넣지 않는다.
 */
final class DeployHistory {

    static final String AUTHOR = "deploy-bot";

    record Deploy(String id, String service, String version, String previousVersion, Instant at) {
    }

    private record Past(String service, String version, Duration before) {
    }

    /** 현재 기준 버전이 언제 배포되었는지. 시나리오와 무관하게 고정이다. */
    private static final List<Past> PAST = List.of(
            new Past("payment-api", Versions.BASELINE.get("payment-api"), Duration.ofDays(6).plusHours(3)),
            new Past("order-api", Versions.BASELINE.get("order-api"), Duration.ofDays(4).plusHours(7)));

    private DeployHistory() {
    }

    static List<Deploy> past(String scenarioId, Instant windowStart) {
        List<Deploy> deploys = new ArrayList<>();
        for (int i = 0; i < PAST.size(); i++) {
            Past p = PAST.get(i);
            deploys.add(new Deploy(DeployIds.of(scenarioId, "history/" + i), p.service(), p.version(), null,
                    windowStart.minus(p.before()).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
        }
        return deploys;
    }

    static ArrayNode toJson(List<Deploy> deploys, Versions versions) {
        List<Deploy> sorted = new ArrayList<>(deploys);
        sorted.sort(Comparator.comparing(Deploy::at).thenComparing(Deploy::id));
        ArrayNode array = Json.array();
        for (Deploy d : sorted) {
            Versions.Info info = versions.info(d.service(), d.version());
            ObjectNode node = array.addObject();
            node.put("id", d.id());
            node.put("service", d.service());
            node.put("version", d.version());
            if (d.previousVersion() == null) {
                node.putNull("previous_version");
            } else {
                node.put("previous_version", d.previousVersion());
            }
            node.put("deployed_at", d.at().toString());
            node.put("author", AUTHOR);
            node.put("summary", info.summary());
            ArrayNode changes = node.putArray("changes");
            info.changes().forEach(changes::add);
        }
        return array;
    }
}
