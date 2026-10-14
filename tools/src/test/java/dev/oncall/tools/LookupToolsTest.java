package dev.oncall.tools;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 조회 툴 3개(search_logs, get_metrics, get_recent_deploys)의 필터, 상한, 입력 거부 */
class LookupToolsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 로그 3줄, 줄 길이 60자, 메트릭 점 5개, 계열 2개, 배포 7일·3건, 결과 16000자 */
    private static final Limits SMALL = new Limits(3, 60, 5, 2, 7, 3, 16000);

    private static Args args(String json) {
        return new Args(JSON.readTree(json));
    }

    private static List<String> texts(ObjectNode out) {
        List<String> t = new ArrayList<>();
        out.path("lines").forEach(l -> t.add(l.path("text").asString()));
        return t;
    }

    // ---------- search_logs ----------

    private static FakeSource logs() {
        String trace = "java.lang.IllegalStateException: boom\n\tat a.b.C.m(C.java:1)\n\t... 5 more\n"
                + "Caused by: java.sql.SQLException: pool timeout\n\tat x.y.Z.q(Z.java:2)";
        return new FakeSource()
                .log("12:01:00", "order-api", FakeSource.appLine("INFO", "Order created", null))
                .log("12:02:00", "order-api", FakeSource.appLine("WARN", "Slow request", null))
                .log("12:03:00", "order-api", FakeSource.appLine("ERROR", "Request failed", trace))
                .log("12:04:00", "postgres", "2026-10-07 12:04:00.000 UTC [27] ERROR:  canceling statement due to statement timeout")
                .log("12:05:00", "postgres", "2026-10-07 12:05:00.000 UTC [27] LOG:  checkpoint starting: time")
                .log("12:20:00", "order-api", FakeSource.appLine("ERROR", "after window", null));
    }

    @Test
    void 레벨_필터는_앱_JSON과_Postgres_형식_모두에_맞는다() {
        LogSearch s = new LogSearch(logs(), Limits.load());
        assertEquals(1, s.run(args("{\"service\":\"order-api\",\"level\":\"error\"}")).path("total_matched").asInt());
        assertEquals(1, s.run(args("{\"service\":\"order-api\",\"level\":\"WARN\"}")).path("total_matched").asInt());
        assertEquals(1, s.run(args("{\"service\":\"postgres\",\"level\":\"ERROR\"}")).path("total_matched").asInt());
        assertEquals(1, s.run(args("{\"service\":\"postgres\",\"level\":\"INFO\"}")).path("total_matched").asInt());
    }

    @Test
    void 기본_구간은_조회_가능_구간이고_밖의_로그는_보이지_않는다() {
        ObjectNode out = new LogSearch(logs(), Limits.load()).run(args("{\"service\":\"order-api\"}"));
        assertEquals(3, out.path("total_matched").asInt());
        assertEquals(FakeSource.END.toString(), out.path("range").path("end").asString());
    }

    @Test
    void contains는_대소문자를_무시한다() {
        ObjectNode out = new LogSearch(logs(), Limits.load()).run(args("{\"service\":\"order-api\",\"contains\":\"SQLEXCEPTION\"}"));
        assertEquals(1, out.path("total_matched").asInt());
    }

    @Test
    void 앱_로그는_펼치고_스택_프레임은_뺀다() {
        ObjectNode out = new LogSearch(logs(), Limits.load()).run(args("{\"service\":\"order-api\",\"level\":\"ERROR\"}"));
        String text = texts(out).getFirst();
        assertEquals("ERROR OrderService: Request failed | java.lang.IllegalStateException: boom"
                + " | Caused by: java.sql.SQLException: pool timeout", text);
    }

    @Test
    void 줄_수_상한이면_가장_최근_줄을_시간순으로_돌려준다() {
        FakeSource src = new FakeSource();
        for (int i = 0; i < 10; i++) {
            src.log("12:0" + i + ":00", "order-api", "line " + i);
        }
        ObjectNode out = new LogSearch(src, SMALL).run(args("{\"service\":\"order-api\"}"));
        assertEquals(10, out.path("total_matched").asInt());
        assertEquals(List.of("line 7", "line 8", "line 9"), texts(out));
        assertEquals(3, out.path("returned").asInt());
        assertTrue(out.has("note"));
        assertEquals(FakeSource.at("12:00:00").toString(), out.path("first_match_at").asString());
    }

    @Test
    void 줄_길이_상한() {
        FakeSource src = new FakeSource().log("12:01:00", "order-api", "x".repeat(200));
        String text = texts(new LogSearch(src, SMALL).run(args("{\"service\":\"order-api\"}"))).getFirst();
        assertTrue(text.startsWith("x".repeat(60)));
        assertTrue(text.length() < 70, text);
    }

    @Test
    void 없는_서비스와_경로_문자는_거부한다() {
        LogSearch s = new LogSearch(logs(), Limits.load());
        assertThrows(ToolInputException.class, () -> s.run(args("{\"service\":\"billing-api\"}")));
        ToolInputException e = assertThrows(ToolInputException.class, () -> s.run(args("{\"service\":\"../meta.json\"}")));
        assertTrue(e.getMessage().contains("order-api"), "가능한 값을 알려 줘야 모델이 고칠 수 있다");
        assertThrows(ToolInputException.class, () -> s.run(args("{}")));
    }

    @Test
    void 모르는_입력_항목_잘못된_시각_레벨은_거부한다() {
        LogSearch s = new LogSearch(logs(), Limits.load());
        assertThrows(ToolInputException.class, () -> s.run(args("{\"service\":\"order-api\",\"query\":\"x\"}")));
        assertThrows(ToolInputException.class, () -> s.run(args("{\"service\":\"order-api\",\"start\":\"어제\"}")));
        assertThrows(ToolInputException.class, () -> s.run(args("{\"service\":\"order-api\",\"level\":\"DEBUG\"}")));
        assertThrows(ToolInputException.class, () -> s.run(args("{\"service\":\"order-api\",\"level\":3}")));
        assertThrows(ToolInputException.class,
                () -> s.run(args("{\"service\":\"order-api\",\"start\":\"2026-10-07T12:05:00Z\",\"end\":\"2026-10-07T12:01:00Z\"}")));
        // 조회 가능 구간과 겹치지 않으면 거부
        assertThrows(ToolInputException.class,
                () -> s.run(args("{\"service\":\"order-api\",\"start\":\"2026-10-08T00:00:00Z\",\"end\":\"2026-10-08T01:00:00Z\"}")));
    }

    @Test
    void 구간은_조회_가능_구간_안으로_자른다() {
        ObjectNode out = new LogSearch(logs(), Limits.load())
                .run(args("{\"service\":\"order-api\",\"start\":\"2026-10-07T11:00:00Z\",\"end\":\"2026-10-07T13:00:00Z\"}"));
        assertEquals(FakeSource.START.toString(), out.path("range").path("start").asString());
        assertEquals(3, out.path("total_matched").asInt());
    }

    // ---------- get_metrics ----------

    private static FakeSource metrics() {
        return new FakeSource()
                .series("db_pool_active", Map.of("service", "order-api"), 1.0, 2.0, null, 10.0, 10.0, 9.0, 8.0, 7.0, 6.0, 5.0)
                .series("db_pool_active", Map.of("service", "payment-api"), 0.0, 0.0)
                .series("db_pool_active", Map.of("service", "other"), 0.0);
    }

    @Test
    void 메트릭_요약과_솎은_점() {
        ObjectNode out = new MetricQuery(metrics(), SMALL).run(args("{\"name\":\"db_pool_active\",\"service\":\"order-api\"}"));
        JsonNode s = out.path("series").get(0);
        assertEquals(10, s.path("summary").path("points_total").asInt());
        assertEquals(1.0, s.path("summary").path("min").asDouble());
        assertEquals(10.0, s.path("summary").path("max").asDouble());
        assertEquals(1.0, s.path("summary").path("first").asDouble());
        assertEquals(5.0, s.path("summary").path("last").asDouble());
        // 점 10개, 상한 5개 → 2칸 간격(0, 2, 4, 6, 8번) + 마지막 점(9번)
        JsonNode pts = s.path("points");
        assertEquals(6, pts.size());
        assertEquals(5.0, pts.get(5).get(1).asDouble(), "마지막 점은 항상 남긴다");
        assertTrue(pts.get(1).get(1).isNull(), "빈 값은 0으로 채우지 않고 null로 둔다");
    }

    @Test
    void 계열_수_상한() {
        ObjectNode out = new MetricQuery(metrics(), SMALL).run(args("{\"name\":\"db_pool_active\"}"));
        assertEquals(3, out.path("series_total").asInt());
        assertEquals(2, out.path("series").size());
        assertTrue(out.has("note"));
    }

    @Test
    void 허용되지_않은_메트릭_이름과_쿼리식은_거부한다() {
        MetricQuery q = new MetricQuery(metrics(), SMALL);
        assertThrows(ToolInputException.class, () -> q.run(args("{\"name\":\"up\"}")));
        assertThrows(ToolInputException.class, () -> q.run(args("{\"name\":\"rate(db_pool_active[5m])\"}")));
        assertThrows(ToolInputException.class, () -> q.run(args("{\"name\":\"db_pool_active\",\"query\":\"x\"}")));
    }

    @Test
    void 점_솎기() {
        List<dev.oncall.adapters.Telemetry.Point> pts = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            pts.add(new dev.oncall.adapters.Telemetry.Point(Instant.ofEpochSecond(i), (double) i));
        }
        List<dev.oncall.adapters.Telemetry.Point> thin = MetricQuery.thin(pts, 40);
        assertTrue(thin.size() <= 41, "솎은 뒤 " + thin.size());
        assertEquals(pts.getFirst(), thin.getFirst());
        assertEquals(pts.getLast(), thin.getLast());
        assertEquals(pts.subList(0, 3), MetricQuery.thin(pts.subList(0, 3), 40));
    }

    // ---------- get_recent_deploys ----------

    private static FakeSource deploys() {
        return new FakeSource()
                .deploy("deploy-old", "order-api", Instant.parse("2026-09-20T00:00:00Z"))
                .deploy("deploy-a", "payment-api", Instant.parse("2026-10-01T00:00:00Z"))
                .deploy("deploy-b", "order-api", Instant.parse("2026-10-03T00:00:00Z"))
                .deploy("deploy-c", "order-api", Instant.parse("2026-10-05T00:00:00Z"))
                .deploy("deploy-d", "order-api", FakeSource.at("12:03:00"))
                .deploy("deploy-future", "order-api", FakeSource.at("12:30:00"));
    }

    private static List<String> ids(ObjectNode out) {
        List<String> ids = new ArrayList<>();
        out.path("deploys").forEach(d -> ids.add(d.path("id").asString()));
        return ids;
    }

    @Test
    void 기본은_조회_가능_구간_끝에서_7일_전까지이고_최근_N건() {
        ObjectNode out = new DeployQuery(deploys(), SMALL).run(args("{}"));
        assertEquals(4, out.path("total").asInt(), "9월 20일 배포와 구간 뒤의 배포는 빠진다");
        assertEquals(List.of("deploy-b", "deploy-c", "deploy-d"), ids(out));
    }

    @Test
    void 서비스로_좁히고_변경_상세는_넣지_않는다() {
        ObjectNode out = new DeployQuery(deploys(), Limits.load()).run(args("{\"service\":\"payment-api\"}"));
        assertEquals(List.of("deploy-a"), ids(out));
        assertFalse(out.path("deploys").get(0).has("changes"));
        assertEquals("1.0.0", out.path("deploys").get(0).path("previous_version").asString());
    }

    @Test
    void 시작을_앞당기면_오래된_배포도_보이지만_미래는_보이지_않는다() {
        ObjectNode out = new DeployQuery(deploys(), Limits.load())
                .run(args("{\"start\":\"2026-09-01T00:00:00Z\",\"end\":\"2026-12-01T00:00:00Z\"}"));
        assertEquals(5, out.path("total").asInt());
        assertFalse(ids(out).contains("deploy-future"));
    }
}
