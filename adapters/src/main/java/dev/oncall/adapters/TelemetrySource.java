package dev.oncall.adapters;

import dev.oncall.adapters.Telemetry.Alert;
import dev.oncall.adapters.Telemetry.DeployRecord;
import dev.oncall.adapters.Telemetry.LogEntry;
import dev.oncall.adapters.Telemetry.MetricSeries;

import java.util.List;
import java.util.Set;

/**
 * 툴이 데이터를 읽는 유일한 통로. 평가는 스냅샷 구현을, 서비스(6주차)는 실시간 구현을 쓴다.
 * 툴은 어느 쪽인지 모른다. 정답이나 주입 시각 같은 평가 전용 정보는 이 인터페이스로 나가지 않는다.
 */
public interface TelemetrySource {

    Alert alert();

    /** 조회할 수 있는 전체 구간 */
    TimeRange available();

    /** 로그를 볼 수 있는 서비스 이름 */
    Set<String> services();

    /** 조회할 수 있는 메트릭 이름 (허용 목록) */
    Set<String> metricNames();

    /** 시간순 */
    List<LogEntry> logs(String service, TimeRange range);

    List<MetricSeries> metric(String name, TimeRange range);

    /** 배포 시각순 */
    List<DeployRecord> deploys(TimeRange range);
}
