package com.shop.payment;

public class StripeGateway implements PaymentGateway {
    @Override
    public boolean charge(String orderId, long amountCents) {
        return amountCents > 0;
    }
}
