package dev.oncall.eval.capture;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * infra/services/versions.yaml. 배포 버전별 변경 요약의 단일 원천이다.
 * 기준 버전(BASELINE)은 docker-compose.yml의 기본값과 같아야 한다.
 */
final class Versions {

    static final String FILE = "infra/services/versions.yaml";
    static final Map<String, String> BASELINE = Map.of("order-api", "1.0.0", "payment-api", "2.0.0");

    record Info(String summary, List<String> changes) {
    }

    private final Map<String, Map<String, Info>> byService;

    Versions(Map<String, Map<String, Info>> byService) {
        this.byService = byService;
    }

    static Versions load(Path root) throws IOException {
        return parse(Files.readString(root.resolve(FILE), StandardCharsets.UTF_8));
    }

    static Versions parse(String yaml) {
        JsonNode tree = Json.YAML.readTree(yaml);
        Map<String, Map<String, Info>> byService = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> service : tree.properties()) {
            Map<String, Info> versions = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> version : service.getValue().properties()) {
                List<String> changes = new ArrayList<>();
                version.getValue().path("changes").forEach(c -> changes.add(c.asString()));
                versions.put(version.getKey(), new Info(version.getValue().path("summary").asString(), changes));
            }
            byService.put(service.getKey(), versions);
        }
        return new Versions(byService);
    }

    Set<String> services() {
        return byService.keySet();
    }

    boolean has(String service, String version) {
        return byService.containsKey(service) && byService.get(service).containsKey(version);
    }

    Info info(String service, String version) {
        if (!has(service, version)) {
            throw new IllegalArgumentException(FILE + "에 없는 버전: " + service + " " + version);
        }
        return byService.get(service).get(version);
    }
}
