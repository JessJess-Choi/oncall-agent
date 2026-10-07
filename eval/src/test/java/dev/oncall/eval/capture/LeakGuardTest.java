package dev.oncall.eval.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeakGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "I/O error on POST request for \"http://toxiproxy:18081/payments\"",
            "Added toxic latency",
            "fault detected",
            "error was injected",
            "chaos monkey",
            "k6 run",
            "loadgen started",
            "scenario pool-exhaustion-01"})
    void 장애_주입_흔적을_찾는다(String line) {
        List<LeakGuard.Leak> leaks = LeakGuard.scan(Map.of("logs.jsonl", "ok\n" + line + "\n"));
        assertEquals(1, leaks.size(), line);
        assertEquals(2, leaks.get(0).lineNumber());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Seoul, Gangnam-gu, Teheran-ro 12, 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            "pay-3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "Using default configuration",
            "Database access failed on POST /orders",
            "HikariPool-1 - Connection is not available"})
    void 평범한_로그는_오탐하지_않는다(String line) {
        assertTrue(LeakGuard.scan(Map.of("logs.jsonl", line)).isEmpty(), line);
    }

    @Test
    void meta_json은_평가_전용이라_검사하지_않는다() {
        assertTrue(LeakGuard.scan(Map.of(CaptureRunner.META, "{\"scenario_id\":\"x\"}")).isEmpty());
    }
}
