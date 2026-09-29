package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;

import java.util.Optional;

/**
 * Persistence seam for bitcoin payments.
 *
 * <p>The default {@link InMemoryBitcoinPaymentStore} keeps the demo self-contained, but the
 * interface exists so a production deployment can back it with the existing SQL Server
 * (via {@link DatabaseHelper}). Durable, externalized state is what allows the service to run
 * as multiple stateless replicas behind a load balancer — the prerequisite for scaling to
 * 100x traffic horizontally.
 */
public interface BitcoinPaymentStore {

    /** Persist a new payment. */
    void save(BitcoinPayment payment);

    /** Look up a payment by its server-generated id. */
    Optional<BitcoinPayment> findById(String paymentId);

    /**
     * Look up an existing payment by client idempotency key, if any. Used to dedupe retried
     * submits so the same logical payment is never created (or charged) twice.
     */
    Optional<BitcoinPayment> findByIdempotencyKey(String idempotencyKey);
}
