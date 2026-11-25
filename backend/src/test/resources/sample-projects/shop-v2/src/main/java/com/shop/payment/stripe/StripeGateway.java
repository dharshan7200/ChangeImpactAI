package com.shop.payment.stripe;

import com.shop.payment.PaymentGateway;

public class StripeGateway implements PaymentGateway {
    @Override
    public boolean charge(String orderId, long amountCents) {
        return amountCents > 0;
    }
}
