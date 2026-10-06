package dev.oncall.sample.order;

import dev.oncall.sample.order.Model.CreateOrderRequest;
import dev.oncall.sample.order.Model.Order;
import dev.oncall.sample.order.Model.OrderNotFoundException;
import dev.oncall.sample.order.Model.Product;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
class OrderService {

    private static final Map<String, Integer> COUPON_RATES = Map.of("WELCOME10", 10, "VIP20", 20);

    private static final String ORDER_COLUMNS =
            "id, customer_id, product_id, quantity, amount, status, coupon_code, shipping_address, created_at";

    private static final RowMapper<Order> ORDER = (rs, i) -> new Order(
            rs.getLong("id"), rs.getLong("customer_id"), rs.getInt("product_id"), rs.getInt("quantity"),
            rs.getLong("amount"), rs.getString("status"), rs.getString("coupon_code"),
            rs.getString("shipping_address"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PaymentClient payments;
    private final ReceiptCache receipts;
    private final Release release;

    OrderService(JdbcTemplate jdbc, TransactionTemplate tx, PaymentClient payments, ReceiptCache receipts,
                 Release release) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.payments = payments;
        this.receipts = receipts;
        this.release = release;
    }

    Order create(CreateOrderRequest req) {
        long price = jdbc.queryForObject("SELECT price FROM products WHERE id = ?", Long.class, req.productId());
        long amount = discounted(price * req.quantity(), req.couponCode());

        Order order;
        if (release.paymentInsideTransaction()) {
            // 1.3.0: 주문 저장, 결제 승인, 상태 갱신을 한 트랜잭션으로 묶었다.
            // 결제 응답(400~600ms)을 기다리는 동안 DB 커넥션을 쥐고 있어 풀이 고갈된다.
            order = tx.execute(s -> {
                long id = insert(req, amount);
                return updateStatus(id, payments.charge(id, amount));
            });
        } else {
            long id = tx.execute(s -> insert(req, amount));
            String status = payments.charge(id, amount);
            order = tx.execute(s -> updateStatus(id, status));
        }

        if (release.receiptCache()) {
            receipts.put(order);
        }
        return order;
    }

    List<Order> recentByCustomer(long customerId) {
        // 인덱스 idx_orders_customer_created가 없으면 수백만 행을 순차 탐색한다 (slow_query).
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM orders WHERE customer_id = ? "
                + "ORDER BY created_at DESC LIMIT 20", ORDER, customerId);
    }

    Order get(long id) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM orders WHERE id = ?", ORDER, id)
                .stream().findFirst().orElseThrow(() -> new OrderNotFoundException(id));
    }

    List<Product> products() {
        return jdbc.query("SELECT id, name, price FROM products ORDER BY id",
                (rs, i) -> new Product(rs.getInt("id"), rs.getString("name"), rs.getLong("price")));
    }

    private long discounted(long amount, String couponCode) {
        String code = release.sharedCouponNormalizer()
                ? CouponCodes.normalize(couponCode)
                : (couponCode == null ? null : CouponCodes.normalize(couponCode));
        if (code == null) {
            return amount;
        }
        Integer rate = COUPON_RATES.get(code);
        if (rate == null) {
            CouponCodes.warnUnknown(code);
            return amount;
        }
        return amount * (100 - rate) / 100;
    }

    private long insert(CreateOrderRequest req, long amount) {
        return jdbc.queryForObject("INSERT INTO orders (customer_id, product_id, quantity, amount, status, "
                        + "coupon_code, shipping_address) VALUES (?, ?, ?, ?, 'PENDING', ?, ?) RETURNING id",
                Long.class, req.customerId(), req.productId(), req.quantity(), amount, req.couponCode(),
                "Seoul, Gangnam-gu, Teheran-ro " + ThreadLocalRandom.current().nextInt(1, 500));
    }

    private Order updateStatus(long id, String paymentStatus) {
        String status = "APPROVED".equals(paymentStatus) ? "PAID" : "PAYMENT_FAILED";
        return jdbc.queryForObject("UPDATE orders SET status = ? WHERE id = ? RETURNING " + ORDER_COLUMNS,
                ORDER, status, id);
    }
}
