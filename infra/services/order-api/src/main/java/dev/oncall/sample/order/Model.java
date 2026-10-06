package dev.oncall.sample.order;

import java.time.Instant;

final class Model {

    private Model() {
    }

    record CreateOrderRequest(long customerId, int productId, int quantity, String couponCode) {
    }

    record Order(long id, long customerId, int productId, int quantity, long amount, String status,
                 String couponCode, String shippingAddress, Instant createdAt) {
    }

    record Product(int id, String name, long price) {
    }

    record PaymentRequest(long orderId, long amount) {
    }

    record PaymentResponse(String paymentId, String status) {
    }

    static final class OrderNotFoundException extends RuntimeException {
        final long id;

        OrderNotFoundException(long id) {
            super("order " + id);
            this.id = id;
        }
    }
}
