package dev.oncall.tools;

import dev.oncall.adapters.TimeRange;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.TreeSet;

/** 툴 입력(JSON) 읽기 도우미. 잘못된 입력은 모델이 고칠 수 있는 메시지로 거부한다. */
final class Args {

    private final JsonNode node;

    Args(JsonNode node) {
        this.node = node == null ? tools.jackson.databind.node.JsonNodeFactory.instance.objectNode() : node;
    }

    /** 허용된 키 외의 것이 있으면 거부한다. */
    Args onlyKeys(Set<String> allowed) {
        Set<String> unknown = new TreeSet<>();
        node.properties().forEach(e -> {
            if (!allowed.contains(e.getKey())) {
                unknown.add(e.getKey());
            }
        });
        if (!unknown.isEmpty()) {
            throw new ToolInputException("모르는 입력 항목: " + unknown + ". 쓸 수 있는 항목: " + new TreeSet<>(allowed));
        }
        return this;
    }

    String optionalString(String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isString() || v.asString().isBlank()) {
            throw new ToolInputException(key + "는 비어 있지 않은 문자열이어야 한다");
        }
        return v.asString().trim();
    }

    String requiredString(String key) {
        String v = optionalString(key);
        if (v == null) {
            throw new ToolInputException(key + "는 필수다");
        }
        return v;
    }

    /** 값이 허용 목록에 있어야 한다 (서비스 이름, 메트릭 이름 등). 경로 문자 같은 이상한 값도 여기서 걸러진다. */
    static String oneOf(String key, String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            throw new ToolInputException(key + " '" + value + "'는 없는 값이다. 가능한 값: " + new TreeSet<>(allowed));
        }
        return value;
    }

    /** start/end(ISO-8601, 예: 2026-10-07T12:10:00Z). 없으면 기본값. 결과는 조회 가능 구간 안으로 자른다. */
    TimeRange range(Instant defaultStart, Instant defaultEnd, TimeRange limit) {
        Instant start = time("start", defaultStart);
        Instant end = time("end", defaultEnd);
        if (end.isBefore(start)) {
            throw new ToolInputException("end가 start보다 앞이다");
        }
        try {
            return limit == null ? new TimeRange(start, end) : new TimeRange(start, end).clampTo(limit);
        } catch (IllegalArgumentException e) {
            throw new ToolInputException(e.getMessage());
        }
    }

    private Instant time(String key, Instant fallback) {
        String v = optionalString(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            throw new ToolInputException(key + "는 ISO-8601 UTC 시각이어야 한다 (예: 2026-10-07T12:10:00Z): " + v);
        }
    }

    JsonNode raw() {
        return node;
    }
}
