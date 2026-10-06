package dev.oncall.sample.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 결제 승인을 흉내 내는 하위 서비스. 응답에 400~600ms가 걸린다.
 * 버전 2.0.0과 2.0.1은 동작이 같다. 2.0.1 배포는 장애와 무관한 미끼로 쓴다.
 */
@SpringBootApplication
@RestController
public class PaymentApiApplication {

    private static final Logger log = LoggerFactory.getLogger(PaymentApiApplication.class);
    private static final Set<String> VERSIONS = Set.of("2.0.0", "2.0.1");

    private final String version;

    PaymentApiApplication(@Value("${app.version}") String version) {
        if (!VERSIONS.contains(version)) {
            throw new IllegalArgumentException("unknown version: " + version);
        }
        this.version = version;
    }

    public static void main(String[] args) {
        SpringApplication.run(PaymentApiApplication.class, args);
    }

    record PaymentRequest(long orderId, long amount) {
    }

    record PaymentResponse(String paymentId, String status) {
    }

    @PostMapping("/payments")
    PaymentResponse charge(@RequestBody PaymentRequest req) throws InterruptedException {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long delay = 400 + random.nextInt(200);
        if (random.nextInt(400) == 0) {
            delay += 800;
            log.warn("Card issuer response delayed: orderId={} took {}ms", req.orderId(), delay);
        }
        Thread.sleep(delay);
        return new PaymentResponse("pay-" + UUID.randomUUID(), "APPROVED");
    }

    @EventListener(ApplicationReadyEvent.class)
    void ready() {
        log.info("payment-api {} started", version);
    }
}
