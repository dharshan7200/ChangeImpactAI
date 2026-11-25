package com.shop.service;

import com.shop.domain.Order;
import com.shop.domain.OrderStatus;
import com.shop.domain.OrderSummary;
import com.shop.payment.PaymentGateway;
import com.shop.repo.OrderRepository;

import java.util.List;
import java.util.Map;

public class OrderService {
    private final OrderRepository repository;
    private PaymentGateway paymentGateway;

    public OrderService(OrderRepository repository, PaymentGateway paymentGateway) {
        this.repository = repository;
        this.paymentGateway = paymentGateway;
    }

    /** Overload 1 */
    public Order placeOrder(String id) {
        return placeOrder(id, 1, null);
    }

    /** Overload 2 */
    public Order placeOrder(String id, int quantity, String couponCode) {
        Order order = new Order(id);
        order.addLine("SKU-" + id, quantity);
        if (paymentGateway.charge(id, 100L * quantity)) {
            order.markPaid();
        }
        return repository.save(order);
    }

    public java.util.Collection<Order> listOrders() {
        return List.of();
    }

    public Map<String, List<OrderSummary>> summarize(String... ids) {
        return Map.of("all", List.of(new OrderSummary(ids[0], OrderStatus.NEW)));
    }

    public String auditTrail() {
        // Type from a library that is not on the analysis classpath: must stay UNRESOLVED.
        return com.acme.audit.AuditClient.current().trail();
    }
}
