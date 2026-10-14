package dev.oncall.adapters;

import java.util.Arrays;
import java.util.Optional;

/**
 * 원인 카테고리의 단일 원천. 시나리오 정의 검사(eval)와 리포트 제출 검사(tools)가 같이 쓴다.
 * 카테고리를 추가하면 라벨, 채점기, docs/SCENARIO_FORMAT.md를 함께 갱신한다.
 */
public enum RootCause {
    SLOW_QUERY("slow_query"),
    CONNECTION_POOL_EXHAUSTION("connection_pool_exhaustion"),
    BAD_DEPLOY("bad_deploy"),
    DOWNSTREAM_TIMEOUT("downstream_timeout"),
    MEMORY_LEAK("memory_leak");

    private final String wireName;

    RootCause(String wireName) {
        this.wireName = wireName;
    }

    /** 파일과 리포트에 쓰는 이름 (예: slow_query) */
    public String wireName() {
        return wireName;
    }

    public static Optional<RootCause> fromWire(String name) {
        return Arrays.stream(values()).filter(c -> c.wireName.equals(name)).findFirst();
    }
}
