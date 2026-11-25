package com.shop.domain;

import java.util.ArrayList;
import java.util.List;

public class Order {
    private final String id;
    private OrderStatus status = OrderStatus.NEW;
    private final List<Line> lines = new ArrayList<>();

    public Order(String id) {
        this.id = id;
    }

    public void addLine(String sku, int quantity) {
        lines.add(new Line(sku, quantity));
    }

    /** Marks the order as paid (comment-only and formatting change). */
    public void markPaid()
    {
        // payment captured
        this.status   =   OrderStatus.PAID;
    }

    public String getId() {
        return id;
    }

    static class Line {
        private final String sku;
        private final int quantity;

        public Line(String sku, int quantity) {
            this.sku = sku;
            this.quantity = quantity;
        }
    }
}
