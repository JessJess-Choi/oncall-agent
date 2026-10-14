package dev.oncall.tools;

import dev.oncall.adapters.RootCause;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * submit_report (원칙 3). 모델이 낸 리포트를 코드로 검사하고, 통과한 것만 파일로 저장한다.
 * 러너는 모델의 답변 텍스트가 아니라 이 파일을 읽는다.
 * 2주차 범위: 형식, 카테고리, 증거 번호 존재. 발췌문(quote)이 원문에 있는지 대조하는 것은 5주차.
 */
final class ReportIntake {

    static final Set<String> KEYS = Set.of("root_cause_category", "summary", "confidence", "evidence",
            "recommended_actions");
    static final Set<String> EVIDENCE_KEYS = Set.of("id", "quote");
    static final Set<String> CONFIDENCE = Set.of("high", "medium", "low", "unknown");
    /** 근거가 부족할 때 쓰는 카테고리. 확신도도 unknown이어야 한다. */
    static final String UNKNOWN = "unknown";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EvidenceStore evidence;
    private final Path out;
    private boolean accepted;

    /** out: 수락한 리포트를 쓸 파일. null이면 쓰지 않는다(테스트용). */
    ReportIntake(EvidenceStore evidence, Path out) {
        this.evidence = evidence;
        this.out = out;
    }

    static Set<String> categories() {
        Set<String> s = Arrays.stream(RootCause.values()).map(RootCause::wireName)
                .collect(Collectors.toCollection(TreeSet::new));
        s.add(UNKNOWN);
        return s;
    }

    synchronized ObjectNode submit(Args args) {
        if (accepted) {
            throw new ToolInputException("리포트는 이미 수락되었다. 다시 제출할 수 없다. 조사를 마쳐라.");
        }
        args.onlyKeys(KEYS);
        JsonNode in = args.raw();
        List<String> errors = new ArrayList<>();

        String category = text(in, "root_cause_category", errors);
        if (category != null && !categories().contains(category)) {
            errors.add("root_cause_category '" + category + "'는 없는 값이다. 가능한 값: " + categories());
        }
        String summary = text(in, "summary", errors);
        String confidence = text(in, "confidence", errors);
        if (confidence != null && !CONFIDENCE.contains(confidence)) {
            errors.add("confidence '" + confidence + "'는 없는 값이다. 가능한 값: " + new TreeSet<>(CONFIDENCE));
        }
        if (UNKNOWN.equals(category) && confidence != null && !UNKNOWN.equals(confidence)) {
            errors.add("root_cause_category가 unknown이면 confidence도 unknown이어야 한다");
        }

        ArrayNode cited = JSON.createArrayNode();
        JsonNode ev = in.get("evidence");
        if (ev == null || !ev.isArray()) {
            errors.add("evidence는 배열이어야 한다 (예: [{\"id\": \"E1\", \"quote\": \"...\"}])");
        } else {
            for (int i = 0; i < ev.size(); i++) {
                checkEvidence(ev.get(i), i, cited, errors);
            }
            if (ev.isEmpty() && confidence != null && !UNKNOWN.equals(confidence)) {
                errors.add("evidence가 비어 있다. 근거 없이 결론을 내리려면 confidence를 unknown으로 한다");
            }
        }

        ArrayNode actions = JSON.createArrayNode();
        JsonNode ra = in.get("recommended_actions");
        if (ra != null && !ra.isNull()) {
            if (!ra.isArray()) {
                errors.add("recommended_actions는 문자열 배열이어야 한다");
            } else {
                for (JsonNode a : ra) {
                    if (!a.isString() || a.asString().isBlank()) {
                        errors.add("recommended_actions의 항목은 비어 있지 않은 문자열이어야 한다");
                        break;
                    }
                    actions.add(a.asString().trim());
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new ToolInputException("리포트가 거부되었다. 고칠 점:\n- " + String.join("\n- ", errors));
        }

        ObjectNode report = JSON.createObjectNode();
        report.put("root_cause_category", category);
        report.put("summary", summary);
        report.put("confidence", confidence);
        report.set("evidence", cited);
        report.set("recommended_actions", actions);
        write(report);
        accepted = true;

        ObjectNode result = JSON.createObjectNode();
        result.put("tool", "submit_report");
        result.put("status", "accepted");
        result.put("message", "리포트를 수락했다. 조사를 마쳐라.");
        return result;
    }

    synchronized boolean accepted() {
        return accepted;
    }

    private void checkEvidence(JsonNode item, int i, ArrayNode cited, List<String> errors) {
        String where = "evidence[" + i + "]";
        if (item == null || !item.isObject()) {
            errors.add(where + "는 {\"id\", \"quote\"} 객체여야 한다");
            return;
        }
        Set<String> unknown = new TreeSet<>();
        item.properties().forEach(e -> {
            if (!EVIDENCE_KEYS.contains(e.getKey())) {
                unknown.add(e.getKey());
            }
        });
        if (!unknown.isEmpty()) {
            errors.add(where + "에 모르는 항목: " + unknown);
        }
        String id = text(item, "id", errors, where + ".id");
        String quote = text(item, "quote", errors, where + ".quote");
        if (id != null && !evidence.has(id)) {
            errors.add(where + ".id '" + id + "'는 발급된 적 없는 증거 번호다. 조회 툴 결과의 evidence_id를 써라");
        }
        if (id != null && quote != null) {
            ObjectNode o = cited.addObject();
            o.put("id", id);
            o.put("quote", quote);
        }
    }

    private static String text(JsonNode n, String key, List<String> errors) {
        return text(n, key, errors, key);
    }

    private static String text(JsonNode n, String key, List<String> errors, String label) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            errors.add(label + "는 필수다");
            return null;
        }
        if (!v.isString() || v.asString().isBlank()) {
            errors.add(label + "는 비어 있지 않은 문자열이어야 한다");
            return null;
        }
        return v.asString().trim();
    }

    /** 반쯤 쓴 파일을 러너가 읽지 않게 임시 파일에 쓰고 옮긴다. */
    private void write(ObjectNode report) {
        if (out == null) {
            return;
        }
        try {
            Path dir = out.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "report", ".tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                    StandardCharsets.UTF_8);
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
