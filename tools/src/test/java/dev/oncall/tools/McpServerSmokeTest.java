package dev.oncall.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 연결 스모크: 서버를 별도 프로세스로 띄워 stdio로 JSON-RPC를 주고받는다.
 * Claude Code가 서버를 붙일 때와 같은 경로(initialize → tools/list → tools/call)를 확인하고,
 * stdout에 MCP 메시지 말고 다른 것(로그 등)이 섞이지 않는지 본다.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class McpServerSmokeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path dir;

    private Process process;
    private OutputStream stdin;
    private final BlockingQueue<String> stdout = new LinkedBlockingQueue<>();
    private final List<String> allStdout = new ArrayList<>();

    private static Path snapshot() {
        Path p = Path.of("").toAbsolutePath();
        while (!Files.exists(p.resolve("settings.gradle.kts"))) {
            p = p.getParent();
        }
        return p.resolve("scenarios/snapshots/pool-exhaustion-01");
    }

    private void start(Path report) throws IOException {
        String java = ProcessHandle.current().info().command().orElse("java");
        process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "dev.oncall.tools.OncallMcpServer",
                "--snapshot", snapshot().toString(), "--report-out", report.toString())
                .redirectError(dir.resolve("stderr.txt").toFile())
                .start();
        stdin = process.getOutputStream();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    stdout.add(line);
                }
            } catch (IOException ignored) {
                // 프로세스 종료
            }
        });
        reader.setDaemon(true);
        reader.start();
    }

    @AfterEach
    void stop() {
        if (process != null) {
            process.destroyForcibly();
        }
    }

    /** stdio 전송은 메시지 하나가 한 줄이다. 메시지 안에 줄바꿈이 있으면 안 된다. */
    private void send(String json) throws IOException {
        stdin.write((json.replace("\n", " ") + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    /** 요청을 보내고 같은 id의 응답이 올 때까지 기다린다. */
    private JsonNode request(int id, String method, String params) throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}");
        while (true) {
            String line = stdout.poll(30, TimeUnit.SECONDS);
            assertNotNull(line, method + " 응답이 오지 않았다. stderr: " + Files.readString(dir.resolve("stderr.txt")));
            allStdout.add(line);
            JsonNode n = JSON.readTree(line);   // JSON이 아닌 줄(로그 등)이 섞이면 여기서 실패한다
            if (n.path("id").asInt(-1) == id) {
                return n;
            }
        }
    }

    private static String text(JsonNode response) {
        return response.path("result").path("content").get(0).path("text").asString();
    }

    @Test
    void initialize_목록_호출_제출까지_stdio로_동작한다() throws Exception {
        Path report = dir.resolve("report.json");
        start(report);

        JsonNode init = request(1, "initialize", """
                {"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}""");
        assertEquals("oncall", init.path("result").path("serverInfo").path("name").asString());
        assertTrue(init.path("result").path("instructions").asString().contains("evidence_id"));
        assertTrue(init.path("result").path("instructions").asString().contains("읽기 전용"), "한글이 UTF-8로 온다");
        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        JsonNode list = request(2, "tools/list", "{}");
        List<String> names = new ArrayList<>();
        list.path("result").path("tools").forEach(t -> names.add(t.path("name").asString()));
        assertEquals(List.of("search_logs", "get_metrics", "get_recent_deploys", "submit_report"), names);
        list.path("result").path("tools").forEach(t ->
                assertEquals("object", t.path("inputSchema").path("type").asString(), t.path("name").asString()));

        JsonNode logs = request(3, "tools/call", """
                {"name":"search_logs","arguments":{"service":"order-api","level":"ERROR"}}""");
        assertFalse(logs.path("result").path("isError").asBoolean(true));
        assertEquals("E1", JSON.readTree(text(logs)).path("evidence_id").asString());

        JsonNode bad = request(4, "tools/call", """
                {"name":"search_logs","arguments":{"service":"../meta.json"}}""");
        assertTrue(bad.path("result").path("isError").asBoolean(false), "잘못된 입력은 툴 오류로 돌려준다");

        JsonNode rejected = request(5, "tools/call", """
                {"name":"submit_report","arguments":{"root_cause_category":"connection_pool_exhaustion",
                 "summary":"s","confidence":"high","evidence":[{"id":"E5","quote":"q"}]}}""");
        assertTrue(rejected.path("result").path("isError").asBoolean(false));
        assertFalse(Files.exists(report));

        JsonNode accepted = request(6, "tools/call", """
                {"name":"submit_report","arguments":{"root_cause_category":"connection_pool_exhaustion",
                 "summary":"s","confidence":"high","evidence":[{"id":"E1","quote":"Connection is not available"}]}}""");
        assertFalse(accepted.path("result").path("isError").asBoolean(true), text(accepted));
        assertEquals("E1", JSON.readTree(report.toFile()).path("evidence").get(0).path("id").asString());

        // stdin을 닫으면 서버가 스스로 끝난다 (러너가 프로세스를 남기지 않게)
        stdin.close();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "stdin을 닫은 뒤 서버가 끝나지 않았다");
        String line;
        while ((line = stdout.poll()) != null) {
            allStdout.add(line);
        }
        for (String l : allStdout) {
            assertEquals("2.0", JSON.readTree(l).path("jsonrpc").asString(), "stdout에는 MCP 메시지만: " + l);
        }
    }
}
