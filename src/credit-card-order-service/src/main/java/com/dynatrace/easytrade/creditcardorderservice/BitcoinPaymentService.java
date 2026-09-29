package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentRequest;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentStatusType;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates asynchronous bitcoin payments.
 *
 * <p>Design (grounded in Bluebox production telemetry — see PR description):
 * <ul>
 *   <li><b>Non-blocking submit.</b> {@link #submit} persists the payment as PENDING and returns
 *       immediately; on-chain confirmation runs off the request path. This keeps user-facing
 *       latency independent of blockchain/third-party-service latency, which is the current
 *       latency outlier in production.</li>
 *   <li><b>Bounded worker pool + hard timeout.</b> Confirmation runs on a fixed pool with a
 *       bounded queue and a per-job timeout, so a hung external call can never starve request
 *       threads or grow memory without limit under 100x load. Timeouts -&gt; EXPIRED, errors -&gt;
 *       FAILED, saturation -&gt; FAILED (fast reject).</li>
 *   <li><b>Idempotency.</b> A client key dedupes retried submits so the same payment is never
 *       created twice — important once retries multiply at 100x.</li>
 *   <li><b>Feature-flag gated.</b> Guarded by {@code bitcoin_payments_enabled} with a short TTL
 *       cache so the per-request flag lookup doesn't add load to feature-flag-service at scale.</li>
 * </ul>
 */
@Service
public class BitcoinPaymentService {
    private static final Logger logger = LoggerFactory.getLogger(BitcoinPaymentService.class);

    public static final String FEATURE_FLAG = "bitcoin_payments_enabled";

    private final BitcoinPaymentStore store;
    private final BitcoinPaymentProcessor processor;
    private final OpenFeatureAPI openFeatureAPI;

    private final ThreadPoolExecutor confirmationPool;
    // A separate, small pool that ONLY runs the blocking processor call, so the timeout wait
    // (on the confirmation pool) and the actual work never share the same thread — this avoids
    // the self-deadlock of a pool waiting on its own queued task.
    private final ExecutorService callPool;
    private final long confirmTimeoutMs;

    // Short TTL cache for the enablement flag to avoid a feature-flag-service call per request.
    private final long flagCacheTtlMs;
    private volatile long flagCachedAt = 0L;
    private volatile boolean flagCachedValue = false;
    private final AtomicLong flagLookups = new AtomicLong();

    public BitcoinPaymentService(BitcoinPaymentStore store, BitcoinPaymentProcessor processor,
            OpenFeatureAPI openFeatureAPI) {
        this.store = store;
        this.processor = processor;
        this.openFeatureAPI = openFeatureAPI;

        int poolSize = envInt("BITCOIN_CONFIRM_POOL_SIZE", 8);
        int queueCapacity = envInt("BITCOIN_CONFIRM_QUEUE_CAPACITY", 1000);
        this.confirmTimeoutMs = envInt("BITCOIN_CONFIRM_TIMEOUT_MS", 5000);
        this.flagCacheTtlMs = envInt("BITCOIN_FLAG_CACHE_TTL_MS", 1000);

        // Bounded queue + CallerRuns would block the request thread, so we instead reject fast
        // and mark the payment FAILED in submit(); the pool itself uses an AbortPolicy.
        this.confirmationPool = new ThreadPoolExecutor(
                poolSize, poolSize, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                daemonFactory("btc-confirm"),
                new ThreadPoolExecutor.AbortPolicy());

        // Cached, unbounded-name daemon pool for the raw blocking call. It is only ever fed
        // from confirmation-pool workers (at most poolSize concurrent), so it stays bounded in
        // practice; interrupted/cancelled calls let its threads die back via the 60s keep-alive.
        this.callPool = Executors.newCachedThreadPool(daemonFactory("btc-call"));
    }

    private static java.util.concurrent.ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread t = new Thread(runnable, name);
            t.setDaemon(true);
            return t;
        };
    }

    /** True when the feature is enabled (TTL-cached to protect feature-flag-service at scale). */
    public boolean isEnabled() {
        long now = System.currentTimeMillis();
        if (now - flagCachedAt < flagCacheTtlMs) {
            return flagCachedValue;
        }
        flagLookups.incrementAndGet();
        final Client client = openFeatureAPI.getClient();
        boolean value = client.getBooleanValue(FEATURE_FLAG, false);
        flagCachedValue = value;
        flagCachedAt = now;
        return value;
    }

    /**
     * Accept a bitcoin payment. Persists it as PENDING and schedules async confirmation.
     * Returns immediately without waiting for on-chain confirmation.
     */
    public BitcoinPayment submit(BitcoinPaymentRequest request) {
        // Idempotent retry: return the existing payment instead of creating a duplicate.
        Optional<BitcoinPayment> existing = store.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            logger.info("Idempotent bitcoin submit hit for key {} -> payment {}",
                    request.idempotencyKey(), existing.get().getPaymentId());
            return existing.get();
        }

        BitcoinPayment payment = new BitcoinPayment(
                UUID.randomUUID().toString(),
                request.accountId(),
                request.amountSatoshis(),
                request.idempotencyKey());
        store.save(payment);
        logger.info("Accepted bitcoin payment {} for account {} ({} sat)",
                payment.getPaymentId(), payment.getAccountId(), payment.getAmountSatoshis());

        try {
            confirmationPool.submit(() -> confirmAsync(payment));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Pool + queue saturated: fail fast rather than block the request thread. The
            // payment stays queryable and is marked FAILED so the client can retry later.
            logger.warn("Bitcoin confirmation pool saturated; failing payment {} fast",
                    payment.getPaymentId());
            payment.settle(BitcoinPaymentStatusType.FAILED,
                    "Confirmation capacity temporarily exhausted; please retry.");
        }
        return payment;
    }

    public Optional<BitcoinPayment> get(String paymentId) {
        return store.findById(paymentId);
    }

    /**
     * Runs on the bounded worker pool. Enforces a hard timeout so a slow/hung external
     * confirmation can never hold a worker indefinitely.
     */
    private void confirmAsync(BitcoinPayment payment) {
        Future<Boolean> future = callPool.submit(() -> processor.confirm(payment));
        try {
            boolean confirmed = future.get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            if (confirmed) {
                payment.settle(BitcoinPaymentStatusType.CONFIRMED,
                        BitcoinPaymentStatusType.CONFIRMED.getDescription());
            } else {
                payment.settle(BitcoinPaymentStatusType.FAILED, "Payment rejected during confirmation.");
            }
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            payment.settle(BitcoinPaymentStatusType.EXPIRED,
                    "Confirmation did not complete within " + confirmTimeoutMs + "ms.");
            logger.warn("Bitcoin payment {} expired after {}ms", payment.getPaymentId(), confirmTimeoutMs);
        } catch (Exception e) {
            future.cancel(true);
            payment.settle(BitcoinPaymentStatusType.FAILED, "Confirmation error: " + e.getMessage());
            logger.error("Bitcoin payment {} failed during confirmation", payment.getPaymentId(), e);
        }
    }

    private static int envInt(String name, int defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
