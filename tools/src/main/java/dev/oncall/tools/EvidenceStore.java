package dev.oncall.tools;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 증거 보관함 (원칙 3). 조회 툴 결과마다 E1, E2…를 붙이고, 모델이 본 결과 원문을 그대로 보관한다.
 * submit_report는 리포트가 인용한 번호가 여기 있는지 확인한다(2주차). 발췌문 대조는 5주차.
 * 서버 프로세스 하나가 조사 한 건이므로, 보관함도 한 건 동안만 산다.
 */
final class EvidenceStore {

    record Evidence(String id, String tool, String arguments, String output, Instant issuedAt) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<String, Evidence> byId = new LinkedHashMap<>();
    private final Path log;
    private int next = 1;

    /** log: 발급한 증거를 한 줄씩 남길 파일(JSONL). null이면 남기지 않는다. */
    EvidenceStore(Path log) {
        this.log = log;
    }

    /** 번호를 먼저 정하고, 그 번호를 넣어 결과를 만든 뒤, 만든 결과 그대로를 보관한다. */
    synchronized String issue(String tool, String arguments, Function<String, String> render) {
        String id = "E" + next++;
        String output = render.apply(id);
        Evidence e = new Evidence(id, tool, arguments, output, Instant.now());
        byId.put(id, e);
        append(e);
        return output;
    }

    synchronized boolean has(String id) {
        return byId.containsKey(id);
    }

    synchronized Optional<Evidence> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    synchronized List<Evidence> all() {
        return new ArrayList<>(byId.values());
    }

    private void append(Evidence e) {
        if (log == null) {
            return;
        }
        ObjectNode n = JSON.createObjectNode();
        n.put("id", e.id());
        n.put("tool", e.tool());
        n.put("arguments", e.arguments());
        n.put("issued_at", e.issuedAt().toString());
        n.put("output", e.output());
        try {
            if (log.getParent() != null) {
                Files.createDirectories(log.getParent());
            }
            Files.writeString(log, JSON.writeValueAsString(n) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
