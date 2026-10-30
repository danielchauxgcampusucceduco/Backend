package com.monitoring.proxyapi.domain.service.support;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Emulates the network + processing time of a remote microservice.
 * <p>
 * {@link ThreadLocalRandom} is used instead of a shared {@code Random} because hundreds of virtual
 * threads hit this method concurrently during a load simulation; a shared instance would serialise
 * them on its internal CAS loop.
 */
public final class LatencySimulator {

    private final int minMillis;
    private final int maxMillis;

    public LatencySimulator(int minMillis, int maxMillis) {
        if (minMillis < 0 || maxMillis < minMillis) {
            throw new IllegalArgumentException("Invalid latency range [" + minMillis + ", " + maxMillis + "]");
        }
        this.minMillis = minMillis;
        this.maxMillis = maxMillis;
    }

    public static LatencySimulator none() {
        return new LatencySimulator(0, 0);
    }

    public void pause() {
        int millis = ThreadLocalRandom.current().nextInt(minMillis, maxMillis + 1);
        if (millis == 0) {
            return;
        }
        try {
            // On a virtual thread, sleep() unmounts it from its carrier, so the simulated latency does
            // not hold an OS thread hostage the way it would on a classic Tomcat worker pool.
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Simulated downstream call interrupted", ex);
        }
    }
}
