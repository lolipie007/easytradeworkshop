package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link BitcoinPaymentStore} for the workshop/demo.
 *
 * <p><b>Not for production at scale:</b> state lives in a single process, so it is lost on
 * restart and is not shared across replicas. Production should implement this interface on
 * top of the existing SQL Server (see {@link DatabaseHelper}) so payment state is durable and
 * horizontally scalable. This is deliberately a drop-in replacement point.
 */
@Component
public class InMemoryBitcoinPaymentStore implements BitcoinPaymentStore {
    private final ConcurrentHashMap<String, BitcoinPayment> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idByIdempotencyKey = new ConcurrentHashMap<>();

    @Override
    public void save(BitcoinPayment payment) {
        byId.put(payment.getPaymentId(), payment);
        if (payment.getIdempotencyKey() != null && !payment.getIdempotencyKey().isBlank()) {
            idByIdempotencyKey.putIfAbsent(payment.getIdempotencyKey(), payment.getPaymentId());
        }
    }

    @Override
    public Optional<BitcoinPayment> findById(String paymentId) {
        return Optional.ofNullable(byId.get(paymentId));
    }

    @Override
    public Optional<BitcoinPayment> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        String id = idByIdempotencyKey.get(idempotencyKey);
        return id == null ? Optional.empty() : findById(id);
    }
}
