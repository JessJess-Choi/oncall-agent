package dev.oncall.eval.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefinitionLoaderTest {

    private static final Duration BASELINE = Duration.ofMinutes(3);

    private final DefinitionLoader loader = new DefinitionLoader(TestSupport.versions(), BASELINE);

    private static final String VALID = """
            id: bad-deploy-01
            service: order-api
            fault:
              type: bad_deploy
              steps:
                - deploy: { service: order-api, version: "1.2.0" }
            background:
              - after: 30s
                deploy: { service: payment-api, version: "2.0.1" }
            alert:
              name: HighErrorRate
              labels: { service: order-api, severity: page }
              timeout: 8m
            """;

    @ParameterizedTest
    @ValueSource(strings = {"slow-query-01", "pool-exhaustion-01", "bad-deploy-01", "downstream-timeout-01",
            "memory-leak-01"})
    void 저장소의_정의_5개를_실제_versions_yaml로_읽는다(String id) throws Exception {
        Path root = TestSupport.repoRoot();
        ScenarioDefinition def = new DefinitionLoader(Versions.load(root), BASELINE).load(root, id);

        assertEquals(id, def.id());
        assertEquals(64, def.sha256().length());
        assertTrue(DefinitionLoader.CATEGORIES.contains(def.faultType()));
    }

    @Test
    void 정상_정의를_단계와_배경_사건으로_해석한다() {
        ScenarioDefinition def = loader.parse(VALID, "bad-deploy-01", "x");

        assertEquals(new Step.Deploy("order-api", "1.2.0"), def.steps().get(0));
        assertEquals(Duration.ofSeconds(30), def.background().get(0).after());
        assertInstanceOf(Step.Deploy.class, def.background().get(0).step());
        assertEquals(Duration.ofMinutes(8), def.alert().timeout());
        assertEquals("page", def.alert().labels().get("severity"));
    }

    @Test
    void 모르는_단계는_거부한다() {
        String yaml = VALID.replace("- deploy: { service: order-api, version: \"1.2.0\" }", "- shell: rm -rf /");
        assertThrows(IllegalArgumentException.class, () -> loader.parse(yaml, "bad-deploy-01", "x"));
    }

    @Test
    void 모르는_키는_거부한다() {
        String yaml = VALID + "extra: true\n";
        assertThrows(IllegalArgumentException.class, () -> loader.parse(yaml, "bad-deploy-01", "x"));
    }

    @Test
    void versions_yaml에_없는_버전은_거부한다() {
        String yaml = VALID.replace("\"1.2.0\"", "\"9.9.9\"");
        assertThrows(IllegalArgumentException.class, () -> loader.parse(yaml, "bad-deploy-01", "x"));
    }

    @Test
    void 따옴표_없는_버전은_숫자로_읽히므로_거부한다() {
        String yaml = VALID.replace("version: \"2.0.1\"", "version: 2.0");
        assertThrows(IllegalArgumentException.class, () -> loader.parse(yaml, "bad-deploy-01", "x"));
    }

    @Test
    void 기준선_구간을_넘는_배경_사건은_거부한다() {
        String yaml = VALID.replace("after: 30s", "after: 3m");
        assertThrows(IllegalArgumentException.class, () -> loader.parse(yaml, "bad-deploy-01", "x"));
    }

    @Test
    void id와_파일_이름이_다르면_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> loader.parse(VALID, "pool-exhaustion-01", "x"));
    }

    @Test
    void 기간_표기를_해석한다() {
        assertEquals(Duration.ofSeconds(30), Durations.parse("30s"));
        assertEquals(Duration.ofHours(1), Durations.parse("1h"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("5 minutes"));
    }
}
