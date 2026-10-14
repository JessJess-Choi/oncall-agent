package dev.oncall.tools;

import dev.oncall.adapters.SnapshotSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 정보 경계: 실제 시험지 5장에서, 모델이 볼 수 있는 모든 것(툴 정의, 기본 조회 결과)에
 * 평가 전용 정보(meta.json의 값, 시나리오 이름)와 장애 주입 흔적이 없는지 확인한다.
 */
class InfoBoundaryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** eval의 LeakGuard.FORBIDDEN과 같은 규칙. 툴 출력 쪽에서 한 번 더 확인한다. */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(fault\\w*|chaos\\w*|inject\\w*|toxi\\w*|k6|loadgen|scenario\\w*)\\b", Pattern.CASE_INSENSITIVE);

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle.kts"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("저장소 루트를 찾지 못함");
        }
        return p;
    }

    @TestFactory
    Stream<DynamicTest> 모델이_보는_출력에_평가_전용_정보가_없다() throws IOException {
        List<Path> snapshots;
        try (Stream<Path> s = Files.list(repoRoot().resolve("scenarios/snapshots"))) {
            snapshots = s.filter(Files::isDirectory).sorted().toList();
        }
        assertTrue(snapshots.size() >= 5, "시험지가 5장 이상 있어야 한다");
        return snapshots.stream().map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(), () -> check(dir)));
    }

    private static void check(Path dir) throws IOException {
        JsonNode meta = JSON.readTree(dir.resolve("meta.json").toFile());
        List<String> secrets = new ArrayList<>(List.of(
                dir.getFileName().toString(),                       // 시나리오 이름
                meta.path("scenario_id").asString(),
                meta.path("definition_sha256").asString(),
                meta.path("git").asString(),
                meta.path("window").path("injected_at").asString(),
                "injected_at", "definition_sha256", "data_seed"));
        // 시나리오 이름에서 번호를 뗀 형태(예: pool-exhaustion)도 정답 힌트다
        secrets.add(dir.getFileName().toString().replaceAll("-\\d+$", ""));

        SnapshotSource source = new SnapshotSource(dir);
        EvidenceStore store = new EvidenceStore(null);
        OncallTools tools = new OncallTools(source, Limits.load(), store, new ReportIntake(store, null));

        List<String> outputs = new ArrayList<>();
        tools.specs().forEach(s -> outputs.add(s.description() + "\n" + s.inputSchema()));
        for (String service : source.services()) {
            outputs.add(tools.call("search_logs", obj("service", service)));
            outputs.add(tools.call("search_logs", obj("service", service).put("level", "ERROR")));
        }
        for (String name : source.metricNames()) {
            outputs.add(tools.call("get_metrics", obj("name", name)));
        }
        outputs.add(tools.call("get_recent_deploys", JSON.createObjectNode()));
        outputs.add(errorMessage(() -> tools.call("search_logs", obj("service", "../meta.json"))));
        outputs.add(errorMessage(() -> tools.call("get_metrics", obj("name", "meta.json"))));

        for (String out : outputs) {
            for (String secret : secrets) {
                assertFalse(out.contains(secret), "'" + secret + "' 누출: " + excerpt(out, out.indexOf(secret)));
            }
            Matcher m = FORBIDDEN.matcher(out);
            assertFalse(m.find(), () -> "주입 흔적 '" + m.group() + "': " + excerpt(out, m.start()));
        }
    }

    private static ObjectNode obj(String key, String value) {
        return JsonNodeFactory.instance.objectNode().put(key, value);
    }

    private static String errorMessage(Runnable r) {
        try {
            r.run();
        } catch (ToolInputException e) {
            return e.getMessage();
        }
        throw new AssertionError("거부되어야 한다");
    }

    private static String excerpt(String s, int at) {
        return s.substring(Math.max(0, at - 80), Math.min(s.length(), at + 80));
    }
}
