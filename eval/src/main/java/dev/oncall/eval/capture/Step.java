package dev.oncall.eval.capture;

import tools.jackson.databind.JsonNode;

import java.time.Duration;

/**
 * 장애 주입 단계. 닫힌 집합이라 정의 파일로 임의 명령을 실행할 수 없다.
 * 배포 기록에 남는 것은 Deploy뿐이다.
 */
sealed interface Step {

    record Deploy(String service, String version) implements Step {
    }

    record Sql(String statement) implements Step {
    }

    /** toxiproxy 독성(지연 등). attributes는 toxiproxy API에 그대로 넘긴다. */
    record Toxic(String proxy, String type, String stream, JsonNode attributes) implements Step {
    }

    record Wait(Duration duration) implements Step {
    }
}
