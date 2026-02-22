package com.hyperprotect.mixin.bridge;

import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sampled error reporter for mixin interceptors.
 *
 * Logs the first occurrence of a fault, then every 100th occurrence.
 * Prevents log flooding from repeated hook errors while still providing
 * diagnostic visibility.
 */
public final class FaultReporter {

    private static final Logger LOGGER = Logger.getLogger("HyperProtect-Mixin");
    private static final long SAMPLE_INTERVAL = 100;

    private final String label;
    private final LongAdder counter = new LongAdder();

    public FaultReporter(String label) {
        this.label = label;
    }

    /**
     * Reports a fault. Logs on first occurrence and every {@value SAMPLE_INTERVAL}th after.
     */
    public void report(Throwable cause) {
        counter.increment();
        long count = counter.sum();
        if (count == 1 || count % SAMPLE_INTERVAL == 0) {
            LOGGER.log(Level.WARNING,
                    "[" + label + "] Hook fault #" + count + ": " + cause.getMessage(), cause);
        }
    }

    /**
     * Reports a fault with a custom message.
     */
    public void report(String detail, Throwable cause) {
        counter.increment();
        long count = counter.sum();
        if (count == 1 || count % SAMPLE_INTERVAL == 0) {
            LOGGER.log(Level.WARNING,
                    "[" + label + "] Hook fault #" + count + ": " + detail, cause);
        }
    }

    /** Total fault count since startup. */
    public long faultCount() {
        return counter.sum();
    }
}
