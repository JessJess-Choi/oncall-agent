package dev.oncall.eval.capture;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 조용히 깨지면 평가나 캡처가 엉뚱하게 동작하는 저장소 설정 파일을 검사한다.
 * 계기: .claude/settings.json이 마지막 쉼표 하나 때문에 0주차부터 올바른 JSON이 아니었는데,
 * 우연히 발견하기 전까지 아무도 몰랐다 (2026-10-10). 방어 장치도 검증되어야 한다.
 */
class RepoConfigTest {

    private static final Path ROOT = TestSupport.repoRoot();

    /** 정답지·시험지·봉인과 비밀값을 지키는 Claude 차단 규칙. 하나라도 빠지면 실패한다. */
    private static final List<String> REQUIRED_DENY = List.of(
            "Edit(./scenarios/labels/**)",
            "Edit(./scenarios/snapshots/**)",
            "Edit(./scenarios/GOLDEN.lock)",
            "Bash(make lock-golden)",
            "Bash(make lock-golden *)",
            "Bash(*lockGolden*)",
            "Bash(*LockGoldenMain*)",
            "Read(./.env)");

    private static String read(String path) throws IOException {
        return Files.readString(ROOT.resolve(path), StandardCharsets.UTF_8);
    }

    @Test
    void 검사에_쓰는_JSON_해석기는_마지막_쉼표를_거부한다() {
        // 이 테스트가 통과해야 아래 settings.json 검사가 오늘 같은 오류를 실제로 잡아낸다.
        assertThrows(RuntimeException.class, () -> Json.parse("{\"deny\": [\"a\", \"b\",]}"));
    }

    @Test
    void claude_설정은_표준_JSON이고_필수_차단_규칙을_모두_가진다() throws IOException {
        JsonNode settings = Json.parse(read(".claude/settings.json"));

        Set<String> deny = new HashSet<>();
        settings.path("permissions").path("deny").forEach(r -> deny.add(r.asString()));
        for (String rule : REQUIRED_DENY) {
            assertTrue(deny.contains(rule), "차단 규칙 없음: " + rule);
        }
    }

    @Test
    void gitattributes는_시나리오_파일의_줄바꿈_변환을_막는다() throws IOException {
        assertTrue(read(".gitattributes").lines().map(String::trim).anyMatch("scenarios/** -text"::equals),
                "scenarios/** -text 규칙이 없으면 줄바꿈 변환으로 GOLDEN.lock이 깨진다");
    }

    @Test
    void 시나리오_정의의_알림_이름은_모두_prometheus_규칙에_있다() throws IOException {
        JsonNode rules = Json.YAML.readTree(read("infra/prometheus/rules.yml"));
        Set<String> alerts = new HashSet<>();
        rules.path("groups").forEach(g -> g.path("rules").forEach(r -> alerts.add(r.path("alert").asString())));

        for (String name : definitionAlertNames()) {
            assertTrue(alerts.contains(name), "rules.yml에 없는 알림: " + name);
        }
    }

    @Test
    void 시나리오_정의의_프록시는_모두_toxiproxy_설정에_있다() throws IOException {
        Set<String> proxies = new HashSet<>();
        Json.parse(read("infra/toxiproxy/toxiproxy.json")).forEach(p -> proxies.add(p.path("name").asString()));

        assertTrue(proxies.containsAll(DefinitionLoader.PROXIES), "toxiproxy.json의 프록시: " + proxies);
    }

    @Test
    void compose의_기준_버전은_캡처_도구의_기준과_같고_중립_별칭을_유지한다() throws IOException {
        JsonNode services = Json.YAML.readTree(read("infra/docker-compose.yml")).path("services");

        assertEquals(Versions.BASELINE.get("order-api"),
                composeDefault(services.path("order-api").path("environment").path("APP_VERSION").asString()));
        assertEquals(Versions.BASELINE.get("payment-api"),
                composeDefault(services.path("payment-api").path("environment").path("APP_VERSION").asString()));

        // 호스트 이름 toxiproxy가 로그·메트릭에 찍히면 장애 주입 흔적(정답 누출)이 된다.
        JsonNode aliases = services.path("toxiproxy").path("networks").path("default").path("aliases");
        assertTrue(aliases.toString().contains("payment-gateway"), "toxiproxy 별칭: " + aliases);
        String paymentUrl = services.path("order-api").path("environment").path("PAYMENT_BASE_URL").asString();
        assertTrue(paymentUrl.startsWith("http://payment-gateway:"), "order-api의 결제 주소: " + paymentUrl);
    }

    @Test
    void alloy의_로그_수집_대상은_캡처_도구의_로그_대상과_같다() throws IOException {
        Matcher m = Pattern.compile("regex\\s*=\\s*\"([^\"]+)\"").matcher(read("infra/alloy/config.alloy"));
        assertTrue(m.find(), "config.alloy에서 수집 대상 정규식을 찾지 못함");

        assertEquals(Set.copyOf(LogExporter.SERVICES), Set.of(m.group(1).split("\\|")),
                "수집 대상이 다르면 시험지에서 로그가 빠지거나 주입 장치 로그가 섞인다");
    }

    /** "${ORDER_API_VERSION:-1.0.0}" → "1.0.0" */
    private static String composeDefault(String value) {
        Matcher m = Pattern.compile("\\$\\{[A-Z_]+:-([^}]+)}").matcher(value);
        assertTrue(m.matches(), "기본값 형식이 아님: " + value);
        return m.group(1);
    }

    /** 정의 파일들의 alert.name 값을 모은다 (정의 형식은 DefinitionLoaderTest가 따로 검사). */
    private static Set<String> definitionAlertNames() throws IOException {
        List<Path> definitions;
        try (Stream<Path> files = Files.list(ROOT.resolve(DefinitionLoader.DIR))) {
            definitions = files.filter(p -> p.toString().endsWith(".yaml")).toList();
        }
        assertTrue(definitions.size() >= 5, "정의 파일을 찾지 못함: " + definitions.size() + "개");

        Set<String> names = new HashSet<>();
        for (Path f : definitions) {
            names.add(Json.YAML.readTree(Files.readString(f, StandardCharsets.UTF_8)).path("alert").path("name")
                    .asString());
        }
        return names;
    }
}
