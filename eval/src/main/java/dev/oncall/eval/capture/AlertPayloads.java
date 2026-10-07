package dev.oncall.eval.capture;

import dev.oncall.eval.capture.ScenarioDefinition.AlertSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Prometheus 알림을 찾고, 실제 당직자가 받는 Alertmanager 웹훅(v4) 형식으로 바꾼다.
 * generatorURL 같은 내부 링크는 넣지 않는다.
 */
final class AlertPayloads {

    private AlertPayloads() {
    }

    /** 정의의 알림 이름과 라벨이 모두 일치하고 firing인 알림 */
    static Optional<JsonNode> findFiring(JsonNode alerts, AlertSpec spec) {
        for (JsonNode alert : alerts) {
            JsonNode labels = alert.path("labels");
            if (!"firing".equals(alert.path("state").asString())
                    || !spec.name().equals(labels.path("alertname").asString())) {
                continue;
            }
            boolean match = spec.labels().entrySet().stream()
                    .allMatch(e -> e.getValue().equals(labels.path(e.getKey()).asString()));
            if (match) {
                return Optional.of(alert);
            }
        }
        return Optional.empty();
    }

    /** 해당 서비스에 걸린 알림이 하나라도 있는지 (pending 포함). 안정화 대기용. */
    static boolean anyFor(JsonNode alerts, String service) {
        for (JsonNode alert : alerts) {
            if (service.equals(alert.path("labels").path("service").asString())) {
                return true;
            }
        }
        return false;
    }

    static ObjectNode toWebhook(JsonNode alert, Instant firedAt) {
        Map<String, String> labels = sorted(alert.path("labels"));
        Map<String, String> annotations = sorted(alert.path("annotations"));

        ObjectNode item = Json.object();
        item.put("status", "firing");
        item.set("labels", node(labels));
        item.set("annotations", node(annotations));
        item.put("startsAt", firedAt.toString());
        item.put("endsAt", "0001-01-01T00:00:00Z");
        item.put("fingerprint", fingerprint(labels));

        ObjectNode payload = Json.object();
        payload.put("version", "4");
        payload.put("status", "firing");
        payload.put("receiver", "oncall");
        payload.set("groupLabels", node(Map.of("alertname", labels.get("alertname"))));
        payload.set("commonLabels", node(labels));
        payload.set("commonAnnotations", node(annotations));
        payload.putArray("alerts").add(item);
        return payload;
    }

    private static Map<String, String> sorted(JsonNode object) {
        Map<String, String> map = new TreeMap<>();
        object.properties().forEach(e -> map.put(e.getKey(), e.getValue().asString()));
        return map;
    }

    private static ObjectNode node(Map<String, String> map) {
        ObjectNode node = Json.object();
        new TreeMap<>(map).forEach(node::put);
        return node;
    }

    private static String fingerprint(Map<String, String> labels) {
        StringBuilder sb = new StringBuilder();
        labels.forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
        return DefinitionLoader.sha256(sb.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }
}
