package com.shop.repo;

import com.shop.domain.Order;

import java.util.Optional;

public interface OrderRepository {
    Optional<Order> findById(String id);

    Order save(Order order);
}
