package com.shop.web;

import com.shop.domain.Order;
import com.shop.repo.OrderRepository;
import com.shop.service.OrderService;

public class OrderController {
    private final OrderService orderService;
    private final OrderRepository orderRepository;

    public OrderController(OrderService orderService, OrderRepository orderRepository) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
    }

    public Order create(String id, int quantity) {
        return orderService.placeOrder(id, quantity, null);
    }

    public Object bulk(String a, String b) {
        return orderService.summarize(a, b);
    }

    /** Layer violation on purpose: controller talks to the repository directly. */
    @GetMapping("/orders/{id}")
    public Order find(String id) {
        return orderRepository.findById(id).orElseThrow();
    }
}
