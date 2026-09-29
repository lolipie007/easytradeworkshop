package com.dynatrace.easytrade.creditcardorderservice.models;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response describing the current state of a bitcoin payment. Returned by both the
 * submit endpoint (as a 202 Accepted body) and the status-poll endpoint.
 *
 * @param paymentId      server-generated identifier used to poll for status
 * @param accountId      the account the payment belongs to
 * @param amountSatoshis payment amount in satoshis
 * @param status         current lifecycle state (pending/confirmed/failed/expired)
 * @param detail         human-readable explanation of the current state
 */
@Schema(description = "State of an asynchronous bitcoin payment.")
public record BitcoinPaymentResponse(
        String paymentId,
        Integer accountId,
        Long amountSatoshis,
        String status,
        String detail) {
}
