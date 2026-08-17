package com.multi.vidulum.cashflow_forecast_processor.app;

public record Transaction(TransactionDetails transactionDetails, PaymentStatus paymentStatus) {

    public boolean isPaid() {
        return PaymentStatus.PAID.equals(paymentStatus);
    }
}
