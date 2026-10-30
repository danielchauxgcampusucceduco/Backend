package com.monitoring.proxyapi.application.proxy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal in-memory cache with a fixed time-to-live, used by the proxy for pure read operations.
 * <p>
 * A full-blown cache library (Caffeine, Redis) would be overkill: entries live a few seconds, the key
 * space is a dozen SKUs and the size bound below is a safety net, not an eviction strategy.
 */
public class TtlResponseCache<T> {

    private static final int MAX_ENTRIES = 512;

    private final ConcurrentHashMap<String, CachedValue<T>> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;
    private final boolean enabled;

    public TtlResponseCache(Duration ttl, Clock clock) {
        this(ttl, clock, true);
    }

    private TtlResponseCache(Duration ttl, Clock clock, boolean enabled) {
        this.ttl = ttl;
        this.clock = clock;
        this.enabled = enabled;
    }

    public static <T> TtlResponseCache<T> disabled(Clock clock) {
        return new TtlResponseCache<>(Duration.ZERO, clock, false);
    }

    public Optional<T> get(String key) {
        if (!enabled) {
            return Optional.empty();
        }
        CachedValue<T> cached = entries.get(key);
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.expiresAt().isBefore(clock.instant())) {
            entries.remove(key, cached);
            return Optional.empty();
        }
        return Optional.of(cached.value());
    }

    public void put(String key, T value) {
        if (!enabled || value == null) {
            return;
        }
        if (entries.size() >= MAX_ENTRIES) {
            Instant now = clock.instant();
            entries.values().removeIf(entry -> entry.expiresAt().isBefore(now));
            if (entries.size() >= MAX_ENTRIES) {
                entries.clear();
            }
        }
        entries.put(key, new CachedValue<>(value, clock.instant().plus(ttl)));
    }

    public void invalidateAll() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    private record CachedValue<T>(T value, Instant expiresAt) {
    }
}
