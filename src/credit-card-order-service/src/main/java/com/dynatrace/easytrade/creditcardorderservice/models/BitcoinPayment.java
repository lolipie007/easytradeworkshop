package com.dynatrace.easytrade.creditcardorderservice.models;

import java.time.OffsetDateTime;

/**
 * A stored bitcoin payment. This is the durable representation persisted by the payment
 * store so that state survives restarts and can be shared across horizontally-scaled
 * replicas (a requirement for handling 100x traffic behind a load balancer).
 */
public class BitcoinPayment {
    private final String paymentId;
    private final Integer accountId;
    private final Long amountSatoshis;
    private final String idempotencyKey;
    private volatile BitcoinPaymentStatusType status;
    private volatile String detail;
    private final OffsetDateTime createdAt;
    private volatile OffsetDateTime updatedAt;

    public BitcoinPayment(String paymentId, Integer accountId, Long amountSatoshis, String idempotencyKey) {
        this.paymentId = paymentId;
        this.accountId = accountId;
        this.amountSatoshis = amountSatoshis;
        this.idempotencyKey = idempotencyKey;
        this.status = BitcoinPaymentStatusType.PENDING;
        this.detail = BitcoinPaymentStatusType.PENDING.getDescription();
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public Integer getAccountId() {
        return accountId;
    }

    public Long getAmountSatoshis() {
        return amountSatoshis;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public BitcoinPaymentStatusType getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Transition to a terminal state. No-op if the payment is already terminal, so a late
     * async result can never overwrite an earlier settled outcome (idempotent transition).
     */
    public synchronized void settle(BitcoinPaymentStatusType newStatus, String detail) {
        if (this.status.isTerminal()) {
            return;
        }
        this.status = newStatus;
        this.detail = detail;
        this.updatedAt = OffsetDateTime.now();
    }

    public BitcoinPaymentResponse toResponse() {
        return new BitcoinPaymentResponse(paymentId, accountId, amountSatoshis, status.getType(), detail);
    }
}
