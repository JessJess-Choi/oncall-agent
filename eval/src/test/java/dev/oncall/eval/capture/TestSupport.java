package dev.oncall.eval.capture;

import java.nio.file.Files;
import java.nio.file.Path;

final class TestSupport {

    static final String VERSIONS_YAML = """
            order-api:
              "1.0.0": { summary: 기준 버전, changes: [] }
              "1.2.0": { summary: 쿠폰 코드 정규화 로직 공통화, changes: [CouponCodes.normalize로 이동] }
            payment-api:
              "2.0.0": { summary: 기준 버전, changes: [] }
              "2.0.1": { summary: 카드사 응답 로그 필드 추가, changes: [orderId 포함] }
            """;

    private TestSupport() {
    }

    static Versions versions() {
        return Versions.parse(VERSIONS_YAML);
    }

    /** 테스트 작업 디렉터리(eval/)에서 저장소 루트를 찾는다. */
    static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle.kts"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("저장소 루트를 찾지 못함");
        }
        return p;
    }
}
