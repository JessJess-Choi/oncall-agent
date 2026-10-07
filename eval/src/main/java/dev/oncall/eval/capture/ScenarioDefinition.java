package dev.oncall.eval.capture;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** scenarios/definitions/<id>.yaml 한 건. DefinitionLoader가 검증해서 만든다. */
record ScenarioDefinition(
        String id,
        String service,
        String faultType,
        List<Step> steps,
        List<Background> background,
        AlertSpec alert,
        String sha256
) {

    /** 장애와 무관한 배경 사건. 기준선 구간 시작(B)에서 after만큼 지난 뒤 실행한다. */
    record Background(Duration after, Step step) {
    }

    /** 캡처를 끝낼 기준이 되는 알림. labels는 모두 일치해야 한다. */
    record AlertSpec(String name, Map<String, String> labels, Duration timeout) {
    }
}
