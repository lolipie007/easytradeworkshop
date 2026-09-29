package com.dynatrace.easytrade.creditcardorderservice.models;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for submitting a bitcoin payment.
 *
 * @param accountId       the account placing the payment
 * @param amountSatoshis  payment amount in satoshis (integer, avoids floating-point drift)
 * @param idempotencyKey  client-supplied key so a retried submit does not create a duplicate
 *                        payment. Optional; when null the service treats each submit as new.
 */
@Schema(description = "Request to initiate an asynchronous bitcoin payment.")
public record BitcoinPaymentRequest(
        Integer accountId,
        Long amountSatoshis,
        @Schema(nullable = true, description = "Optional client key to make retried submits idempotent.")
        String idempotencyKey) {
}
