package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentRequest;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentStatusType;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;

public class BitcoinPaymentServiceTests {

    /** Polls until the payment reaches a terminal state or the deadline passes. */
    private static void awaitTerminal(BitcoinPayment payment, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !payment.getStatus().isTerminal()) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private OpenFeatureAPI featureApiReturning(boolean enabled) {
        OpenFeatureAPI api = Mockito.mock(OpenFeatureAPI.class);
        Client client = Mockito.mock(Client.class);
        Mockito.when(api.getClient()).thenReturn(client);
        Mockito.when(client.getBooleanValue(eq(BitcoinPaymentService.FEATURE_FLAG), Mockito.anyBoolean()))
                .thenReturn(enabled);
        return api;
    }

    private BitcoinPaymentRequest request() {
        return new BitcoinPaymentRequest(42, 100_000L, null);
    }

    @Test
    void submitReturnsPendingImmediatelyAndConfirmsAsync() {
        BitcoinPaymentStore store = new InMemoryBitcoinPaymentStore();
        BitcoinPaymentProcessor processor = payment -> true; // always confirms, fast
        BitcoinPaymentService service = new BitcoinPaymentService(store, processor, featureApiReturning(true));

        BitcoinPayment payment = service.submit(request());
        assertNotNull(payment.getPaymentId());
        // Submit must not block on confirmation: state is PENDING right away.
        assertEquals(BitcoinPaymentStatusType.PENDING, payment.getStatus());

        awaitTerminal(payment, 3000);
        assertEquals(BitcoinPaymentStatusType.CONFIRMED, payment.getStatus());
    }

    @Test
    void rejectedPaymentBecomesFailed() {
        BitcoinPaymentStore store = new InMemoryBitcoinPaymentStore();
        BitcoinPaymentProcessor processor = payment -> false; // rejects
        BitcoinPaymentService service = new BitcoinPaymentService(store, processor, featureApiReturning(true));

        BitcoinPayment payment = service.submit(request());
        awaitTerminal(payment, 3000);
        assertEquals(BitcoinPaymentStatusType.FAILED, payment.getStatus());
    }

    @Test
    void processorErrorBecomesFailed() {
        BitcoinPaymentStore store = new InMemoryBitcoinPaymentStore();
        BitcoinPaymentProcessor processor = payment -> {
            throw new RuntimeException("processor blew up");
        };
        BitcoinPaymentService service = new BitcoinPaymentService(store, processor, featureApiReturning(true));

        BitcoinPayment payment = service.submit(request());
        awaitTerminal(payment, 3000);
        assertEquals(BitcoinPaymentStatusType.FAILED, payment.getStatus());
        assertTrue(payment.getDetail().contains("processor blew up"));
    }

    @Test
    void idempotentSubmitReturnsSamePayment() {
        BitcoinPaymentStore store = new InMemoryBitcoinPaymentStore();
        BitcoinPaymentProcessor processor = payment -> true;
        BitcoinPaymentService service = new BitcoinPaymentService(store, processor, featureApiReturning(true));

        BitcoinPaymentRequest req = new BitcoinPaymentRequest(7, 50_000L, "key-123");
        BitcoinPayment first = service.submit(req);
        BitcoinPayment second = service.submit(req);
        assertEquals(first.getPaymentId(), second.getPaymentId());
    }

    @Test
    void differentIdempotencyKeysCreateDistinctPayments() {
        BitcoinPaymentStore store = new InMemoryBitcoinPaymentStore();
        BitcoinPaymentProcessor processor = payment -> true;
        BitcoinPaymentService service = new BitcoinPaymentService(store, processor, featureApiReturning(true));

        BitcoinPayment a = service.submit(new BitcoinPaymentRequest(7, 50_000L, "key-A"));
        BitcoinPayment b = service.submit(new BitcoinPaymentRequest(7, 50_000L, "key-B"));
        assertNotEquals(a.getPaymentId(), b.getPaymentId());
    }

    @Test
    void enabledReflectsFeatureFlag() {
        BitcoinPaymentStore store = new InMemoryBitcoinPaymentStore();
        BitcoinPaymentProcessor processor = payment -> true;

        BitcoinPaymentService enabled = new BitcoinPaymentService(store, processor, featureApiReturning(true));
        assertTrue(enabled.isEnabled());

        BitcoinPaymentService disabled = new BitcoinPaymentService(store, processor, featureApiReturning(false));
        assertFalse(disabled.isEnabled());
    }

    @Test
    void getReturnsStoredPayment() {
        BitcoinPaymentStore store = new InMemoryBitcoinPaymentStore();
        BitcoinPaymentProcessor processor = payment -> true;
        BitcoinPaymentService service = new BitcoinPaymentService(store, processor, featureApiReturning(true));

        BitcoinPayment payment = service.submit(request());
        assertTrue(service.get(payment.getPaymentId()).isPresent());
        assertTrue(service.get("does-not-exist").isEmpty());
    }
}
