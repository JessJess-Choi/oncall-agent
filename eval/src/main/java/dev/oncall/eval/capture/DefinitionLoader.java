package dev.oncall.eval.capture;

import dev.oncall.eval.capture.ScenarioDefinition.AlertSpec;
import dev.oncall.eval.capture.ScenarioDefinition.Background;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 시나리오 정의를 읽고 검증한다. 모르는 키와 단계는 거부한다.
 * 정의 파일은 AI도 수정할 수 있으므로, 실행 가능한 동작을 여기서 닫힌 집합으로 묶는다.
 */
final class DefinitionLoader {

    static final String DIR = "scenarios/definitions";
    static final Set<String> CATEGORIES =
            Set.of("slow_query", "connection_pool_exhaustion", "bad_deploy", "downstream_timeout", "memory_leak");
    static final Set<String> PROXIES = Set.of("payment");

    private final Versions versions;
    private final Duration baseline;

    DefinitionLoader(Versions versions, Duration baseline) {
        this.versions = versions;
        this.baseline = baseline;
    }

    ScenarioDefinition load(Path root, String id) throws IOException {
        Path file = root.resolve(DIR).resolve(id + ".yaml");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("정의 파일 없음: " + DIR + "/" + id + ".yaml");
        }
        byte[] bytes = Files.readAllBytes(file);
        return parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), id, sha256(bytes));
    }

    ScenarioDefinition parse(String yaml, String expectedId, String sha256) {
        JsonNode root = Json.YAML.readTree(yaml);
        onlyKeys(root, "정의", Set.of("id", "service", "fault", "background", "alert"), Set.of("background"));

        String id = text(root, "id", "정의");
        if (!id.equals(expectedId)) {
            throw invalid("id '" + id + "'가 파일 이름 '" + expectedId + "'와 다르다");
        }
        String service = text(root, "service", "정의");
        if (!versions.services().contains(service)) {
            throw invalid("모르는 서비스: " + service);
        }

        JsonNode fault = root.get("fault");
        onlyKeys(fault, "fault", Set.of("type", "steps"), Set.of());
        String type = text(fault, "type", "fault");
        if (!CATEGORIES.contains(type)) {
            throw invalid("모르는 원인 카테고리: " + type);
        }
        List<Step> steps = new ArrayList<>();
        JsonNode stepNodes = fault.get("steps");
        if (!stepNodes.isArray() || stepNodes.isEmpty()) {
            throw invalid("fault.steps는 비어 있지 않은 목록이어야 한다");
        }
        for (int i = 0; i < stepNodes.size(); i++) {
            steps.add(step(stepNodes.get(i), "fault.steps[" + i + "]", Set.of()));
        }

        List<Background> background = new ArrayList<>();
        JsonNode bgNodes = root.path("background");
        for (int i = 0; i < bgNodes.size(); i++) {
            JsonNode bg = bgNodes.get(i);
            String where = "background[" + i + "]";
            Duration after = Durations.parse(text(bg, "after", where));
            if (after.compareTo(baseline) >= 0) {
                throw invalid(where + ".after는 기준선 구간(" + baseline + ")보다 짧아야 한다");
            }
            background.add(new Background(after, step(bg, where, Set.of("after"))));
        }

        JsonNode alert = root.get("alert");
        onlyKeys(alert, "alert", Set.of("name", "labels", "timeout"), Set.of());
        Map<String, String> labels = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : alert.get("labels").properties()) {
            labels.put(e.getKey(), e.getValue().asString());
        }
        AlertSpec alertSpec = new AlertSpec(text(alert, "name", "alert"), labels,
                Durations.parse(text(alert, "timeout", "alert")));

        return new ScenarioDefinition(id, service, type, List.copyOf(steps), List.copyOf(background), alertSpec,
                sha256);
    }

    /** 단계 노드는 deploy/sql/toxic/wait 중 정확히 하나를 가진다 (extra는 함께 허용되는 키). */
    private Step step(JsonNode node, String where, Set<String> extra) {
        Set<String> kinds = new TreeSet<>(names(node));
        kinds.removeAll(extra);
        if (kinds.size() != 1) {
            throw invalid(where + "는 deploy, sql, toxic, wait 중 하나만 가져야 한다: " + kinds);
        }
        String kind = kinds.iterator().next();
        JsonNode body = node.get(kind);
        String at = where + "." + kind;
        return switch (kind) {
            case "deploy" -> {
                onlyKeys(body, at, Set.of("service", "version"), Set.of());
                String service = text(body, "service", at);
                String version = text(body, "version", at);
                if (!versions.has(service, version)) {
                    throw invalid(at + ": " + Versions.FILE + "에 없는 버전 " + service + " " + version);
                }
                yield new Step.Deploy(service, version);
            }
            case "sql" -> {
                if (!body.isString() || body.asString().isBlank()) {
                    throw invalid(at + "는 SQL 문자열이어야 한다");
                }
                yield new Step.Sql(body.asString());
            }
            case "toxic" -> {
                onlyKeys(body, at, Set.of("proxy", "type", "stream", "attributes"), Set.of());
                String proxy = text(body, "proxy", at);
                if (!PROXIES.contains(proxy)) {
                    throw invalid(at + ": 모르는 프록시 " + proxy);
                }
                String stream = text(body, "stream", at);
                if (!stream.equals("upstream") && !stream.equals("downstream")) {
                    throw invalid(at + ".stream은 upstream 또는 downstream");
                }
                if (!body.path("attributes").isObject()) {
                    throw invalid(at + ".attributes는 객체여야 한다");
                }
                yield new Step.Toxic(proxy, text(body, "type", at), stream, body.get("attributes"));
            }
            case "wait" -> new Step.Wait(Durations.parse(body.asString()));
            default -> throw invalid(where + ": 모르는 단계 '" + kind + "'");
        };
    }

    private static void onlyKeys(JsonNode node, String where, Set<String> allowed, Set<String> optional) {
        if (node == null || !node.isObject()) {
            throw invalid(where + "는 객체여야 한다");
        }
        for (String name : names(node)) {
            if (!allowed.contains(name)) {
                throw invalid(where + ": 모르는 키 '" + name + "'");
            }
        }
        for (String name : allowed) {
            if (!optional.contains(name) && !node.has(name)) {
                throw invalid(where + ": 필수 키 '" + name + "' 없음");
            }
        }
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.properties().forEach(e -> names.add(e.getKey()));
        return names;
    }

    private static String text(JsonNode node, String key, String where) {
        JsonNode v = node.get(key);
        if (v == null || !v.isString() || v.asString().isBlank()) {
            throw invalid(where + "." + key + "는 문자열이어야 한다 (버전은 \"1.0.0\"처럼 따옴표로)");
        }
        return v.asString();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("시나리오 정의 오류: " + message);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
