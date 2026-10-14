package dev.oncall.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** submit_report: 2주차 범위는 형식, 카테고리, 증거 번호 존재. 발췌문 대조는 5주차. */
class ReportIntakeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path dir;

    private Path out;
    private ReportIntake intake;

    @BeforeEach
    void setUp() {
        EvidenceStore store = new EvidenceStore(null);
        store.issue("search_logs", "{}", id -> "결과 " + id);  // E1
        store.issue("get_metrics", "{}", id -> "결과 " + id);  // E2
        out = dir.resolve("out/report.json");
        intake = new ReportIntake(store, out);
    }

    private static Args args(String json) {
        return new Args(JSON.readTree(json));
    }

    private static final String VALID = """
            {"root_cause_category":"connection_pool_exhaustion","summary":"커넥션 풀이 가득 찼다",
             "confidence":"high","evidence":[{"id":"E1","quote":"Connection is not available"}],
             "recommended_actions":["트랜잭션 범위 축소"]}""";

    private String reject(String json) {
        ToolInputException e = assertThrows(ToolInputException.class, () -> intake.submit(args(json)));
        assertFalse(Files.exists(out), "거부된 리포트는 저장하지 않는다");
        return e.getMessage();
    }

    @Test
    void 올바른_리포트는_수락하고_파일로_저장한다() {
        JsonNode result = intake.submit(args(VALID));
        assertEquals("accepted", result.path("status").asString());
        assertTrue(intake.accepted());
        JsonNode saved = JSON.readTree(out.toFile());
        assertEquals("connection_pool_exhaustion", saved.path("root_cause_category").asString());
        assertEquals("E1", saved.path("evidence").get(0).path("id").asString());
        assertEquals("트랜잭션 범위 축소", saved.path("recommended_actions").get(0).asString());
    }

    @Test
    void 두_번째_제출은_거부한다() {
        intake.submit(args(VALID));
        assertThrows(ToolInputException.class, () -> intake.submit(args(VALID)));
    }

    @Test
    void 발급된_적_없는_증거_번호는_거부한다() {
        String msg = reject(VALID.replace("\"E1\"", "\"E9\""));
        assertTrue(msg.contains("E9"), msg);
    }

    @Test
    void 모르는_카테고리와_확신도는_거부한다() {
        assertTrue(reject(VALID.replace("connection_pool_exhaustion", "disk_full")).contains("disk_full"));
        assertTrue(reject(VALID.replace("\"high\"", "\"very_high\"")).contains("very_high"));
    }

    @Test
    void 필수_항목_누락과_모르는_항목은_거부한다() {
        reject("{\"root_cause_category\":\"slow_query\",\"confidence\":\"high\",\"evidence\":[{\"id\":\"E1\",\"quote\":\"q\"}]}");
        reject(VALID.replace("\"summary\":\"커넥션 풀이 가득 찼다\"", "\"summary\":\"  \""));
        reject(VALID.replace("\"recommended_actions\"", "\"severity\":\"P1\",\"recommended_actions\""));
        reject(VALID.replace("\"quote\":\"Connection is not available\"", "\"quote\":\"q\",\"page\":1"));
        reject(VALID.replace(",\"quote\":\"Connection is not available\"", ""));
    }

    @Test
    void 틀린_곳을_한_번에_모두_알려_준다() {
        String msg = reject("{\"root_cause_category\":\"disk_full\",\"summary\":\"s\",\"confidence\":\"sure\","
                + "\"evidence\":[{\"id\":\"E7\",\"quote\":\"q\"}]}");
        assertTrue(msg.contains("disk_full") && msg.contains("sure") && msg.contains("E7"), msg);
    }

    @Test
    void 근거가_없으면_확신도는_unknown이어야_한다() {
        reject(VALID.replace("[{\"id\":\"E1\",\"quote\":\"Connection is not available\"}]", "[]"));
        intake.submit(args("{\"root_cause_category\":\"unknown\",\"summary\":\"근거 부족\",\"confidence\":\"unknown\",\"evidence\":[]}"));
        assertTrue(Files.exists(out));
    }

    @Test
    void 카테고리가_unknown이면_확신도도_unknown() {
        reject("{\"root_cause_category\":\"unknown\",\"summary\":\"s\",\"confidence\":\"high\","
                + "\"evidence\":[{\"id\":\"E1\",\"quote\":\"q\"}]}");
    }

    @Test
    void 거부된_뒤에는_고쳐서_다시_제출할_수_있다() {
        reject(VALID.replace("\"E1\"", "\"E9\""));
        assertEquals("accepted", intake.submit(args(VALID)).path("status").asString());
    }
}
