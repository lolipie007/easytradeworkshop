package com.dynatrace.easytrade.creditcardorderservice.models;

import lombok.Getter;

/**
 * Lifecycle states for an asynchronous bitcoin payment.
 *
 * <p>Bitcoin settlement is slow and variable (block times), so confirmation happens
 * off the request path. A payment is accepted as {@link #PENDING} immediately, then an
 * async worker moves it to a terminal state ({@link #CONFIRMED}, {@link #FAILED} or
 * {@link #EXPIRED}).
 */
@Getter
public enum BitcoinPaymentStatusType {
    PENDING("pending", "Bitcoin payment accepted and awaiting on-chain confirmation."),
    CONFIRMED("confirmed", "Bitcoin payment confirmed on-chain."),
    FAILED("failed", "Bitcoin payment could not be confirmed."),
    EXPIRED("expired", "Bitcoin payment confirmation timed out before completing.");

    private final String type;
    private final String description;

    BitcoinPaymentStatusType(String type, String description) {
        this.type = type;
        this.description = description;
    }

    public boolean isTerminal() {
        return this != PENDING;
    }
}
