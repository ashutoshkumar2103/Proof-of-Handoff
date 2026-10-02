package com.handoffly.common.web;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.RateLimitedException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small in-memory sliding-window limiter: "at most N of this kind per window, per key". The key says whose
 * allowance it is (an address, an email, an account), so one caller's attempts never use up another's.
 * It lives in this one application instance; with several instances each keeps its own count, so the effective
 * limit is per instance. State is only a list of recent timestamps per key and is dropped as it ages out.
 */
@Component
public class RateLimiter {

    private static final int SWEEP_EVERY = 500;
    private static final Duration LONGEST_WINDOW = Duration.ofHours(24);

    private final Map<String, ArrayDeque<Long>> hits = new ConcurrentHashMap<>();
    private final boolean enabled;
    private int calls;

    public RateLimiter(HandOfflyProperties properties) {
        this.enabled = properties.getRateLimit().isEnabled();
    }

    /**
     * Counts one attempt under {@code key} and refuses it if the key has already used up its allowance.
     * @throws RateLimitedException when {@code max} attempts were already made within {@code window}
     */
    public void hit(String key, int max, Duration window) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        long windowMillis = window.toMillis();
        ArrayDeque<Long> times = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            evictOld(times, now - windowMillis);
            if (times.size() >= max) {
                throw new RateLimitedException((times.peekFirst() + windowMillis - now + 999) / 1000);
            }
            times.addLast(now);
        }
        maybeSweep(now);
    }

    /** True while the key has already used up its allowance — without counting anything. */
    public boolean isLimited(String key, int max, Duration window) {
        if (!enabled) return false;
        ArrayDeque<Long> times = hits.get(key);
        if (times == null) return false;
        synchronized (times) {
            evictOld(times, System.currentTimeMillis() - window.toMillis());
            return times.size() >= max;
        }
    }

    /** Forgets a key's attempts (e.g. after a successful sign-in). */
    public void reset(String key) {
        hits.remove(key);
    }

    private static void evictOld(ArrayDeque<Long> times, long oldest) {
        while (!times.isEmpty() && times.peekFirst() <= oldest) times.pollFirst();
    }

    /**
     * Now and then, drops keys with nothing recent left, so the map cannot grow without bound. Nothing is
     * kept longer than {@link #LONGEST_WINDOW}, so no limit may use a longer window than that.
     */
    private void maybeSweep(long now) {
        boolean sweep;
        synchronized (this) {
            sweep = ++calls % SWEEP_EVERY == 0;
        }
        if (sweep) {
            hits.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    evictOld(e.getValue(), now - LONGEST_WINDOW.toMillis());
                    return e.getValue().isEmpty();
                }
            });
        }
    }
}
