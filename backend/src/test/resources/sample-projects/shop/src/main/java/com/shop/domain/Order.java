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

    public void markPaid() {
        this.status = OrderStatus.PAID;
    }

    public String getId() {
        return id;
    }

    public static class Line {
        private final String sku;
        private final int quantity;

        public Line(String sku, int quantity) {
            this.sku = sku;
            this.quantity = quantity;
        }
    }
}
