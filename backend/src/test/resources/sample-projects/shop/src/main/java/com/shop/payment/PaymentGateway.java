package com.shop.payment;

public interface PaymentGateway {
    boolean charge(String orderId, long amountCents);
}
