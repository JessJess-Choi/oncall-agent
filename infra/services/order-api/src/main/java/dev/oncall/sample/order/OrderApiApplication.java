package dev.oncall.sample.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication
@EnableScheduling
public class OrderApiApplication {

    private static final Logger log = LoggerFactory.getLogger(OrderApiApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(OrderApiApplication.class, args);
    }

    @Bean
    Release release(@Value("${app.version}") String version) {
        return Release.of(version);
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    void ready(ApplicationReadyEvent event) {
        log.info("order-api {} started", event.getApplicationContext().getBean(Release.class).version());
    }
}
