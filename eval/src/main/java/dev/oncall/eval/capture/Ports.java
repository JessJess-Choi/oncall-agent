package dev.oncall.eval.capture;

import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * CaptureRunner가 바깥 세계와 만나는 경계. 테스트에서는 가짜 구현으로 바꾼다.
 */
final class Ports {

    private Ports() {
    }

    interface Prometheus {
        /** Prometheus 서버 시각. 호스트와 Docker VM의 시계 오차를 피하려고 캡처의 기준 시계로 쓴다. */
        Instant now();

        /** /api/v1/alerts의 data.alerts */
        JsonNode alerts();

        /** /api/v1/query_range의 data.result (matrix) */
        JsonNode queryRange(String query, Instant start, Instant end, Duration step);
    }

    interface Loki {
        /** /loki/api/v1/query_range의 data.result (streams), 오래된 것부터 */
        JsonNode queryRange(String logql, Instant start, Instant end, int limit);
    }

    interface Stack {
        /** 스택이 떠 있고 필요한 서비스가 응답하는지. 아니면 예외. */
        void checkReady();

        /** 기준선 복원: 기준 버전, 인덱스, toxic 없음. 배포 기록에 남기지 않는다. */
        void reset();

        boolean orderApiHealthy();

        void deploy(String service, String version);

        void sql(String statement);

        void addToxic(Step.Toxic toxic);

        Map<String, String> currentVersions();

        /** git 커밋과 작업 트리 변경 여부 (meta.json용) */
        String gitDescribe();
    }

    interface Pacer {
        void sleep(Duration duration);
    }
}
