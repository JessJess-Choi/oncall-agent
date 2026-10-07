package dev.oncall.eval.capture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpClientsTest {

    private final List<Duration> sleeps = new ArrayList<>();

    @Test
    void 시간_초과는_재시도해서_성공하면_결과를_돌려준다() {
        AtomicInteger calls = new AtomicInteger();
        String result = HttpClients.withRetry(3, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new UncheckedIOException(new IOException("request timed out"));
            }
            return "ok";
        }, sleeps::add);

        assertEquals("ok", result);
        assertEquals(3, calls.get());
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(10)), sleeps);
    }

    @Test
    void 끝까지_실패하면_시도_횟수를_담아_실패한다() {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> HttpClients.withRetry(3, () -> {
            calls.incrementAndGet();
            throw new UncheckedIOException(new IOException("request timed out"));
        }, sleeps::add));

        assertEquals(3, calls.get());
        assertEquals("요청 실패 (3회 시도): request timed out", e.getMessage());
    }

    @Test
    void HTTP_오류_응답은_재시도하지_않는다() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> HttpClients.withRetry(3, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("GET /api → 400");
        }, sleeps::add));

        assertEquals(1, calls.get());
    }
}
