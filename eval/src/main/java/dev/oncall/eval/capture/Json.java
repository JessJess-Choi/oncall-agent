package dev.oncall.eval.capture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * JSON/YAML 공용 도구. 스냅샷은 GOLDEN.lock으로 바이트 해시를 고정하므로,
 * 출력은 항상 노드를 만든 순서 그대로, LF 줄바꿈으로 쓴다.
 */
final class Json {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final YAMLMapper YAML = YAMLMapper.builder().build();

    private Json() {
    }

    static ObjectNode object() {
        return JSON.createObjectNode();
    }

    static ArrayNode array() {
        return JSON.createArrayNode();
    }

    static JsonNode parse(String json) {
        return JSON.readTree(json);
    }

    /** 사람이 읽을 파일용. 운영체제와 무관하게 LF로 끝난다. */
    static String pretty(JsonNode node) {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node).replace("\r\n", "\n") + "\n";
    }

    /** 한 줄짜리 JSON (logs.jsonl 등). */
    static String compact(JsonNode node) {
        return JSON.writeValueAsString(node);
    }
}
