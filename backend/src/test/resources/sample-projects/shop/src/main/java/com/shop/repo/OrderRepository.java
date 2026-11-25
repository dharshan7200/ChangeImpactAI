package com.shop.repo;

import com.shop.domain.Order;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Optional<Order> findById(String id);

    List<Order> findAll();

    Order save(Order order);
}
