package com.shop.service;

public class DiscountService {
    public int discountPercent(String couponCode) {
        return couponCode == null ? 0 : 10;
    }
}
