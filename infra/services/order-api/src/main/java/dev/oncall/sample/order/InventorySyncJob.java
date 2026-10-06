package dev.oncall.sample.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 평소에도 있는 배경 로그. 가끔 "slow" 경고를 내지만 장애와는 무관하다 (노이즈).
 */
@Component
class InventorySyncJob {

    private static final Logger log = LoggerFactory.getLogger(InventorySyncJob.class);

    private final JdbcTemplate jdbc;

    InventorySyncJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 30_000)
    void sync() throws InterruptedException {
        long start = System.nanoTime();
        Integer products = jdbc.queryForObject("SELECT count(*) FROM products", Integer.class);
        if (ThreadLocalRandom.current().nextInt(5) == 0) {
            Thread.sleep(1200 + ThreadLocalRandom.current().nextInt(400));
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        if (ms >= 1000) {
            log.warn("Inventory sync slow: {} products in {}ms", products, ms);
        } else {
            log.info("Inventory sync completed: {} products in {}ms", products, ms);
        }
    }
}
