package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;

/**
 * Abstraction over the (slow, external) bitcoin confirmation mechanism.
 *
 * <p>Implementations reach out to the blockchain / third-party gateway to confirm a payment.
 * This call is expected to be slow and unreliable, which is exactly why it is invoked from a
 * bounded, timeout-guarded worker pool rather than on the request path.
 *
 * <p>Keeping this an interface means the confirmation transport can later be swapped for a
 * message-queue consumer (e.g. the RabbitMQ service already deployed in this environment)
 * without touching the controller or service.
 */
public interface BitcoinPaymentProcessor {

    /**
     * Attempt to confirm the payment on-chain. May block for a long time; callers are
     * responsible for enforcing a timeout.
     *
     * @return true if the payment was confirmed, false if it was rejected
     * @throws Exception on transport/processing failure
     */
    boolean confirm(BitcoinPayment payment) throws Exception;
}
