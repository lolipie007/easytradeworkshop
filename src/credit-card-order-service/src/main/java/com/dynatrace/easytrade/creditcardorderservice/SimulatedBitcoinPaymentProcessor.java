package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Default, self-contained implementation used for the workshop/demo. It simulates the
 * latency and occasional failure of real on-chain confirmation WITHOUT making an
 * un-instrumented external network call.
 *
 * <p>In production this would be replaced by an implementation that talks to
 * third-party-service / a real blockchain node. Because the seam is an interface and the
 * caller enforces a hard timeout, swapping it in carries no risk to the request path.
 */
@Component
public class SimulatedBitcoinPaymentProcessor implements BitcoinPaymentProcessor {
    private static final Logger logger = LoggerFactory.getLogger(SimulatedBitcoinPaymentProcessor.class);

    @Override
    public boolean confirm(BitcoinPayment payment) throws InterruptedException {
        // Simulate variable on-chain confirmation latency (0.2s - 1.5s).
        long simulatedLatencyMs = ThreadLocalRandom.current().nextLong(200, 1500);
        logger.info("Simulating bitcoin confirmation for payment {} ({}ms)", payment.getPaymentId(),
                simulatedLatencyMs);
        Thread.sleep(simulatedLatencyMs);

        // Simulate a small rejection rate (~5%).
        boolean confirmed = ThreadLocalRandom.current().nextInt(100) >= 5;
        logger.info("Simulated bitcoin confirmation result for payment {}: {}", payment.getPaymentId(),
                confirmed ? "CONFIRMED" : "REJECTED");
        return confirmed;
    }
}
