package dev.oncall.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 분배, 증거 번호 발급·보관, 결과 크기 상한, 툴 정의와 입력 검사의 일치 */
class OncallToolsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static FakeSource source() {
        FakeSource s = new FakeSource();
        for (int i = 0; i < 10; i++) {
            s.log("12:0" + i + ":00", "order-api", FakeSource.appLine("ERROR", "failure number " + i + " " + "y".repeat(80), null));
        }
        s.series("db_pool_active", Map.of("service", "order-api"), 1.0, 2.0);
        return s;
    }

    private static JsonNode json(String s) {
        return JSON.readTree(s);
    }

    @Test
    void 조회_결과마다_번호를_붙이고_보관한_원문은_돌려준_원문과_같다(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("evidence.jsonl");
        EvidenceStore store = new EvidenceStore(log);
        OncallTools tools = new OncallTools(source(), Limits.load(), store, new ReportIntake(store, null));

        String r1 = tools.call("search_logs", json("{\"service\":\"order-api\"}"));
        String r2 = tools.call("get_metrics", json("{\"name\":\"db_pool_active\"}"));
        String r3 = tools.call("get_recent_deploys", json("{}"));

        assertEquals("E1", json(r1).path("evidence_id").asString());
        assertEquals("E2", json(r2).path("evidence_id").asString());
        assertEquals("E3", json(r3).path("evidence_id").asString());
        assertTrue(r1.startsWith("{\"evidence_id\":\"E1\""), "번호가 맨 앞에 있어야 모델이 찾기 쉽다");
        assertEquals(r1, store.get("E1").orElseThrow().output());
        assertEquals("search_logs", store.get("E1").orElseThrow().tool());

        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertEquals(r2, json(lines.get(1)).path("output").asString());
    }

    @Test
    void 잘못된_입력은_번호를_쓰지_않는다() {
        EvidenceStore store = new EvidenceStore(null);
        OncallTools tools = new OncallTools(source(), Limits.load(), store, new ReportIntake(store, null));
        assertThrows(ToolInputException.class, () -> tools.call("search_logs", json("{\"service\":\"nope\"}")));
        assertThrows(ToolInputException.class, () -> tools.call("run_shell", json("{}")));
        assertThrows(ToolInputException.class, () -> tools.call("search_logs", json("[1,2]")));
        assertEquals("E1", json(tools.call("get_recent_deploys", json("{}"))).path("evidence_id").asString());
    }

    @Test
    void 리포트는_같은_보관함의_번호로_검사한다() {
        EvidenceStore store = new EvidenceStore(null);
        OncallTools tools = new OncallTools(source(), Limits.load(), store, new ReportIntake(store, null));
        String report = "{\"root_cause_category\":\"slow_query\",\"summary\":\"s\",\"confidence\":\"low\","
                + "\"evidence\":[{\"id\":\"E1\",\"quote\":\"q\"}]}";
        assertThrows(ToolInputException.class, () -> tools.call("submit_report", json(report)));
        tools.call("search_logs", json("{\"service\":\"order-api\"}"));
        assertEquals("accepted", json(tools.call("submit_report", json(report))).path("status").asString());
        assertEquals(1, store.all().size(), "submit_report는 증거 번호를 발급하지 않는다");
    }

    @Test
    void 결과_크기_상한을_넘으면_오래된_줄부터_뺀다() {
        Limits tight = new Limits(10, 500, 40, 12, 7, 20, 1200);
        EvidenceStore store = new EvidenceStore(null);
        OncallTools tools = new OncallTools(source(), tight, store, new ReportIntake(store, null));
        String out = tools.call("search_logs", json("{\"service\":\"order-api\"}"));
        assertTrue(out.length() <= 1200, "길이 " + out.length());
        JsonNode n = json(out);
        assertTrue(n.has("truncated"));
        JsonNode lines = n.path("lines");
        assertTrue(lines.size() < 10 && !lines.isEmpty());
        assertTrue(lines.get(lines.size() - 1).path("text").asString().contains("failure number 9"), "최근 줄은 남긴다");
        assertEquals(lines.size(), n.path("returned").asInt());
        assertEquals(out, store.get("E1").orElseThrow().output(), "보관 원문도 줄인 결과와 같다");
    }

    @Test
    void 툴_정의의_입력_항목은_코드가_받는_항목과_같다() {
        EvidenceStore store = new EvidenceStore(null);
        OncallTools tools = new OncallTools(source(), Limits.load(), store, new ReportIntake(store, null));
        Map<String, Set<String>> keys = Map.of(
                "search_logs", LogSearch.KEYS,
                "get_metrics", MetricQuery.KEYS,
                "get_recent_deploys", DeployQuery.KEYS,
                "submit_report", ReportIntake.KEYS);
        assertEquals(keys.keySet(), new HashSet<>(tools.specs().stream().map(OncallTools.Spec::name).toList()));
        for (OncallTools.Spec spec : tools.specs()) {
            JsonNode schema = json(spec.inputSchema());
            Set<String> props = new HashSet<>(schema.path("properties").propertyNames());
            assertEquals(keys.get(spec.name()), props, spec.name());
            assertTrue(!schema.path("additionalProperties").asBoolean(true), spec.name());
        }
    }

    @Test
    void 출력_상한은_설정_파일에서_읽는다() {
        Limits l = Limits.load();
        assertTrue(l.logMaxLines() > 0 && l.resultMaxChars() > l.logLineMaxChars());
        assertTrue((long) l.logMaxLines() * l.logLineMaxChars() < l.resultMaxChars(),
                "보통의 로그 결과가 결과 크기 상한에 걸리지 않게 둔다");
    }
}
