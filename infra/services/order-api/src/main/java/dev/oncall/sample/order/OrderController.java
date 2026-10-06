package dev.oncall.sample.order;

import dev.oncall.sample.order.Model.CreateOrderRequest;
import dev.oncall.sample.order.Model.Order;
import dev.oncall.sample.order.Model.Product;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class OrderController {

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/orders")
    Order create(@RequestBody CreateOrderRequest request) {
        return orders.create(request);
    }

    @GetMapping("/orders")
    List<Order> recent(@RequestParam long customerId) {
        return orders.recentByCustomer(customerId);
    }

    @GetMapping("/orders/{id}")
    Order get(@PathVariable long id) {
        return orders.get(id);
    }

    @GetMapping("/products")
    List<Product> products() {
        return orders.products();
    }
}
