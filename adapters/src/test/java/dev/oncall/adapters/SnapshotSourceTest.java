package dev.oncall.adapters;

import dev.oncall.adapters.Telemetry.DeployRecord;
import dev.oncall.adapters.Telemetry.LogEntry;
import dev.oncall.adapters.Telemetry.MetricSeries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotSourceTest {

    @TempDir
    Path dir;

    private static Instant t(String hhmmss) {
        return Instant.parse("2026-10-07T" + hhmmss + "Z");
    }

    private SnapshotSource write() throws IOException {
        Files.writeString(dir.resolve("alert.json"), """
                {"alerts":[{"labels":{"alertname":"HighErrorRate","service":"order-api"},
                "annotations":{"summary":"에러율 높음"},"startsAt":"2026-10-07T12:08:00Z"}]}""", StandardCharsets.UTF_8);
        // 일부러 시간 순서를 섞어 둔다
        Files.writeString(dir.resolve("logs.jsonl"), String.join("\n",
                "{\"ts\":\"2026-10-07T12:05:00Z\",\"service\":\"order-api\",\"line\":\"b\"}",
                "{\"ts\":\"2026-10-07T12:01:00Z\",\"service\":\"order-api\",\"line\":\"a\"}",
                "{\"ts\":\"2026-10-07T12:02:00Z\",\"service\":\"postgres\",\"line\":\"pg\"}",
                ""), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("metrics.json"), """
                {"step_seconds":60,"start":"2026-10-07T12:00:00Z","end":"2026-10-07T12:10:00Z","series":[
                 {"name":"db_pool_active","labels":{"service":"order-api"},
                  "points":[[1791374400,1.0],[1791374460,null],[1791374520,3.0]]},
                 {"name":"error_ratio","labels":{"service":"order-api"},"points":[[1791374400,0.0]]}]}""",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("deploys.json"), """
                [{"id":"deploy-2","service":"order-api","version":"1.1.0","previous_version":"1.0.0",
                  "deployed_at":"2026-10-07T12:03:00Z","author":"bot","summary":"s2","changes":["c"]},
                 {"id":"deploy-1","service":"order-api","version":"1.0.0","previous_version":null,
                  "deployed_at":"2026-10-01T00:00:00Z","author":"bot","summary":"s1","changes":[]}]""",
                StandardCharsets.UTF_8);
        // 평가 전용 파일. 깨진 JSON이어도 SnapshotSource는 열지 않으므로 문제가 없어야 한다.
        Files.writeString(dir.resolve("meta.json"), "{ 깨진 파일", StandardCharsets.UTF_8);
        return new SnapshotSource(dir);
    }

    @Test
    void 허용_파일_목록에_meta_json이_없다() {
        assertFalse(SnapshotSource.ALLOWED_FILES.contains("meta.json"));
        assertEquals(List.of("alert.json", "logs.jsonl", "metrics.json", "deploys.json"), SnapshotSource.ALLOWED_FILES);
    }

    @Test
    void meta_json이_깨져_있어도_읽는다_열지_않으므로() throws IOException {
        SnapshotSource s = write();
        assertEquals("HighErrorRate", s.alert().name());
        assertEquals(t("12:08:00"), s.alert().startsAt());
    }

    @Test
    void 조회_가능_구간은_메트릭의_시작과_끝() throws IOException {
        assertEquals(new TimeRange(t("12:00:00"), t("12:10:00")), write().available());
    }

    @Test
    void 로그는_시간순이고_서비스와_구간으로_거른다() throws IOException {
        SnapshotSource s = write();
        assertEquals(Set.of("order-api", "postgres"), s.services());
        List<LogEntry> all = s.logs("order-api", s.available());
        assertEquals(List.of("a", "b"), all.stream().map(LogEntry::line).toList());
        List<LogEntry> early = s.logs("order-api", new TimeRange(t("12:00:00"), t("12:02:00")));
        assertEquals(List.of("a"), early.stream().map(LogEntry::line).toList());
        assertTrue(s.logs("payment-api", s.available()).isEmpty());
    }

    @Test
    void 메트릭은_이름으로_고르고_빈_값은_null로_둔다() throws IOException {
        SnapshotSource s = write();
        assertEquals(Set.of("db_pool_active", "error_ratio"), s.metricNames());
        List<MetricSeries> m = s.metric("db_pool_active", s.available());
        assertEquals(1, m.size());
        assertEquals(3, m.getFirst().points().size());
        assertNull(m.getFirst().points().get(1).value());
        assertEquals(1, s.metric("db_pool_active", new TimeRange(t("12:00:00"), t("12:00:30"))).getFirst().points().size());
    }

    @Test
    void 배포는_시각순이고_이전_버전은_없을_수_있다() throws IOException {
        SnapshotSource s = write();
        List<DeployRecord> d = s.deploys(new TimeRange(Instant.EPOCH, t("12:10:00")));
        assertEquals(List.of("deploy-1", "deploy-2"), d.stream().map(DeployRecord::id).toList());
        assertNull(d.getFirst().previousVersion());
        assertEquals(List.of("c"), d.getLast().changes());
    }

    @Test
    void 파일이_없으면_파일_이름만_알리고_폴더_경로는_넣지_않는다() {
        // 폴더 이름이 시나리오 이름이라, 메시지에 경로가 들어가면 정답 힌트가 샐 수 있다
        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> new SnapshotSource(dir));
        assertTrue(e.getMessage().contains("alert.json"), e.getMessage());
        assertFalse(e.getMessage().contains(dir.getFileName().toString()), e.getMessage());
    }

    @Test
    void 구간_자르기와_잘못된_구간() {
        TimeRange outer = new TimeRange(t("12:00:00"), t("12:10:00"));
        assertEquals(new TimeRange(t("12:05:00"), t("12:10:00")),
                new TimeRange(t("12:05:00"), t("12:30:00")).clampTo(outer));
        assertThrows(IllegalArgumentException.class, () -> new TimeRange(t("12:30:00"), t("12:40:00")).clampTo(outer));
        assertThrows(IllegalArgumentException.class, () -> new TimeRange(t("12:10:00"), t("12:00:00")));
    }

    @Test
    void 원인_카테고리_이름_변환() {
        assertEquals(RootCause.SLOW_QUERY, RootCause.fromWire("slow_query").orElseThrow());
        assertTrue(RootCause.fromWire("SLOW_QUERY").isEmpty());
        assertEquals(5, RootCause.values().length);
    }
}
