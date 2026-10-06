package dev.oncall.sample.order;

import dev.oncall.sample.order.Model.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.4.0: 영수증 재조회를 빠르게 하려고 렌더링한 HTML을 보관한다. 만료도 상한도 없다.
 * 주문 1건당 약 16KB라서, 초당 25건이면 힙 256MB가 몇 분 안에 찬다.
 */
@Component
class ReceiptCache {

    private static final String TEMPLATE = buildTemplate();

    private final Map<Long, String> receipts = new ConcurrentHashMap<>();

    void put(Order order) {
        receipts.put(order.id(), TEMPLATE
                .replace("{{id}}", Long.toString(order.id()))
                .replace("{{amount}}", Long.toString(order.amount()))
                .replace("{{status}}", order.status()));
    }

    private static String buildTemplate() {
        StringBuilder sb = new StringBuilder("<html><head><style>");
        for (int i = 0; sb.length() < 16_000; i++) {
            sb.append(".r").append(i).append("{margin:0 auto;padding:4px 8px;font:12px/1.4 sans-serif;color:#333}");
        }
        return sb.append("</style></head><body><h1>Receipt {{id}}</h1><p>{{amount}} KRW, {{status}}</p></body></html>")
                .toString();
    }
}
