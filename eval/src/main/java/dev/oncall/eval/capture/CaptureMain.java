package dev.oncall.eval.capture;

import java.nio.file.Path;
import java.time.Duration;

/** make capture SCENARIO=<id> 진입점. 실행 중인 스택(make up)이 필요하다. */
public final class CaptureMain {

    static final String PROMETHEUS = "http://localhost:9090";
    static final String LOKI = "http://localhost:3100";
    static final String TOXIPROXY = "http://localhost:8474";
    static final String ORDER_API = "http://localhost:8080";

    public static void main(String[] args) {
        if (args.length != 1 || args[0].isBlank()) {
            System.err.println("사용법: make capture SCENARIO=<id>");
            System.exit(2);
        }
        Path root = Path.of("").toAbsolutePath();
        try {
            CaptureRunner.Settings settings = CaptureRunner.Settings.defaults();
            Versions versions = Versions.load(root);
            ScenarioDefinition def = new DefinitionLoader(versions, settings.baseline()).load(root, args[0]);
            HttpClients.HttpPrometheus prometheus = new HttpClients.HttpPrometheus(PROMETHEUS);
            CaptureRunner runner = new CaptureRunner(
                    new DockerStack(root, TOXIPROXY, ORDER_API),
                    prometheus,
                    new LogExporter(new HttpClients.HttpLoki(LOKI), Duration.ofSeconds(60), 50_000),
                    new MetricExporter(prometheus, MetricExporter.loadDefs(), Duration.ofSeconds(15)),
                    d -> {
                        try {
                            Thread.sleep(d.toMillis());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("중단됨", e);
                        }
                    },
                    new SnapshotWriter(root),
                    versions,
                    settings,
                    System.out);
            runner.run(def);
        } catch (Exception e) {
            System.err.println("[capture] 실패: " + e.getMessage());
            System.exit(1);
        }
    }
}
