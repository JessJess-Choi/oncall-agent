package dev.oncall.eval.capture;

import dev.oncall.eval.capture.DeployHistory.Deploy;
import dev.oncall.eval.capture.ScenarioDefinition.Background;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 캡처 한 건의 흐름:
 * 사전 검사 → 기준선 복원 → 안정화 → 기준선 구간(배경 사건) → 장애 주입 → 알림 대기 → 꼬리 구간
 * → 추출 → 누출 검사 → 스냅샷 작성. 성공·실패와 무관하게 마지막에 기준선을 복원한다.
 * 모든 시각은 Prometheus 서버 시각을 기준으로 한다.
 */
final class CaptureRunner {

    static final String ALERT = "alert.json";
    static final String LOGS = "logs.jsonl";
    static final String METRICS = "metrics.json";
    static final String DEPLOYS = "deploys.json";
    static final String META = "meta.json";

    /** infra/postgres/init/01-schema.sql의 setseed 값 */
    static final double DATA_SEED = 0.42;

    record Settings(Duration baseline, Duration settle, Duration settleTimeout, Duration tail, Duration ingestLag,
                    Duration poll) {
        static Settings defaults() {
            return new Settings(Duration.ofMinutes(3), Duration.ofSeconds(60), Duration.ofMinutes(5),
                    Duration.ofMinutes(1), Duration.ofSeconds(10), Duration.ofSeconds(5));
        }
    }

    private final Ports.Stack stack;
    private final Ports.Prometheus prometheus;
    private final LogExporter logs;
    private final MetricExporter metrics;
    private final Ports.Pacer pacer;
    private final SnapshotWriter writer;
    private final Versions versions;
    private final Settings settings;
    private final PrintStream out;

    CaptureRunner(Ports.Stack stack, Ports.Prometheus prometheus, LogExporter logs, MetricExporter metrics,
                  Ports.Pacer pacer, SnapshotWriter writer, Versions versions, Settings settings, PrintStream out) {
        this.stack = stack;
        this.prometheus = prometheus;
        this.logs = logs;
        this.metrics = metrics;
        this.pacer = pacer;
        this.writer = writer;
        this.versions = versions;
        this.settings = settings;
        this.out = out;
    }

    Path run(ScenarioDefinition def) throws IOException {
        writer.refuseExisting(def.id());
        stack.checkReady();
        try {
            return capture(def);
        } finally {
            say("기준선 복원 (정리)");
            try {
                stack.reset();
            } catch (RuntimeException e) {
                say("경고: 기준선 복원 실패. 수동으로 make up을 다시 실행할 것: " + e.getMessage());
            }
        }
    }

    private Path capture(ScenarioDefinition def) throws IOException {
        say("기준선 복원");
        stack.reset();
        settle(def.service());

        Instant windowStart = prometheus.now();
        say("기준선 구간 시작 " + windowStart + " (" + settings.baseline().toSeconds() + "초)");
        List<Deploy> deploys = new ArrayList<>(DeployHistory.past(def.id(), windowStart));

        List<Background> background = new ArrayList<>(def.background());
        background.sort(Comparator.comparing(Background::after));
        for (int i = 0; i < background.size(); i++) {
            Background bg = background.get(i);
            sleepUntil(windowStart.plus(bg.after()));
            say("배경 사건: " + bg.step());
            execute(def.id(), "background/" + i, bg.step(), deploys);
        }
        sleepUntil(windowStart.plus(settings.baseline()));

        Instant injectedAt = prometheus.now();
        for (int i = 0; i < def.steps().size(); i++) {
            say("장애 주입: " + def.steps().get(i));
            execute(def.id(), "fault/" + i, def.steps().get(i), deploys);
        }

        say("알림 대기: " + def.alert().name() + " " + def.alert().labels());
        Instant deadline = prometheus.now().plus(def.alert().timeout());
        JsonNode alert = null;
        Instant firedAt = null;
        while (alert == null) {
            Optional<JsonNode> found = AlertPayloads.findFiring(prometheus.alerts(), def.alert());
            Instant now = prometheus.now();
            if (found.isPresent()) {
                alert = found.get();
                firedAt = now;
            } else if (now.isAfter(deadline)) {
                throw new IllegalStateException("알림이 " + def.alert().timeout() + " 안에 울리지 않음. 스냅샷을 만들지 않는다");
            } else {
                pacer.sleep(settings.poll());
            }
        }
        say("알림 발생 " + firedAt + " (주입 후 " + Duration.between(injectedAt, firedAt).toSeconds() + "초)");

        Instant windowEnd = firedAt.plus(settings.tail());
        sleepUntil(windowEnd);
        pacer.sleep(settings.ingestLag());
        Map<String, String> versionsAtEnd = new TreeMap<>(stack.currentVersions());

        say("추출: " + windowStart + " ~ " + windowEnd);
        Map<String, String> files = new LinkedHashMap<>();
        files.put(ALERT, Json.pretty(AlertPayloads.toWebhook(alert, firedAt)));
        files.put(LOGS, LogExporter.toJsonl(logs.export(windowStart, windowEnd)));
        files.put(METRICS, Json.pretty(metrics.export(windowStart, windowEnd)));
        files.put(DEPLOYS, Json.pretty(DeployHistory.toJson(deploys, versions)));
        files.put(META, Json.pretty(meta(def, windowStart, injectedAt, firedAt, windowEnd, versionsAtEnd)));

        List<LeakGuard.Leak> leaks = LeakGuard.scan(files);
        if (!leaks.isEmpty()) {
            leaks.stream().limit(20).forEach(l -> say("  누출: " + l));
            throw new IllegalStateException("스냅샷에 장애 주입 흔적 " + leaks.size() + "건. 스냅샷을 만들지 않는다");
        }
        Path target = writer.write(def.id(), files);
        say("스냅샷 작성: " + target);
        return target;
    }

    private void execute(String scenarioId, String position, Step step, List<Deploy> deploys) {
        switch (step) {
            case Step.Deploy d -> {
                String previous = stack.currentVersions().get(d.service());
                Instant at = prometheus.now();
                stack.deploy(d.service(), d.version());
                deploys.add(new Deploy(DeployIds.of(scenarioId, position), d.service(), d.version(), previous, at));
            }
            case Step.Sql s -> stack.sql(s.statement());
            case Step.Toxic t -> stack.addToxic(t);
            case Step.Wait w -> pacer.sleep(w.duration());
        }
    }

    /** 앱이 살아 있고, 정해진 시간이 지나고, 이전 캡처의 알림이 모두 풀릴 때까지 */
    private void settle(String service) {
        Instant deadline = prometheus.now().plus(settings.settleTimeout());
        while (!stack.orderApiHealthy()) {
            if (prometheus.now().isAfter(deadline)) {
                throw new IllegalStateException("order-api가 정상 상태가 되지 않음");
            }
            pacer.sleep(settings.poll());
        }
        say("안정화 대기 " + settings.settle().toSeconds() + "초");
        pacer.sleep(settings.settle());
        while (AlertPayloads.anyFor(prometheus.alerts(), service)) {
            if (prometheus.now().isAfter(deadline.plus(settings.settle()))) {
                throw new IllegalStateException(service + "에 남은 알림이 풀리지 않음");
            }
            pacer.sleep(settings.poll());
        }
    }

    private void sleepUntil(Instant t) {
        Duration remaining = Duration.between(prometheus.now(), t);
        if (!remaining.isNegative() && !remaining.isZero()) {
            pacer.sleep(remaining);
        }
    }

    private ObjectNode meta(ScenarioDefinition def, Instant start, Instant injected, Instant fired, Instant end,
                            Map<String, String> versionsAtEnd) {
        ObjectNode meta = Json.object();
        meta.put("scenario_id", def.id());
        meta.put("note", "평가 전용 파일. 에이전트 툴에 노출하지 않는다");
        ObjectNode window = meta.putObject("window");
        window.put("start", start.toString());
        window.put("injected_at", injected.toString());
        window.put("alert_fired_at", fired.toString());
        window.put("end", end.toString());
        ObjectNode v = meta.putObject("service_versions");
        versionsAtEnd.forEach(v::put);
        meta.put("data_seed", DATA_SEED);
        meta.put("definition_sha256", def.sha256());
        meta.put("git", stack.gitDescribe());
        return meta;
    }

    private void say(String message) {
        out.println("[capture] " + message);
    }
}
