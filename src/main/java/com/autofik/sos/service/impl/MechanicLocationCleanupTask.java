package com.autofik.sos.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Evicts mechanics from the Redis geo cache once they've gone quiet for too long (no GPS ping
 * received), so findAvailable() never returns a mechanic who's actually gone offline mid-job,
 * lost connectivity, or closed the app without calling the availability toggle.
 * <p>
 * Relies on "mechanics:lastseen", a sorted set keyed by mechanicId and scored by the epoch
 * millis of their last ping — written alongside every GEOADD in MechanicServiceImpl.writeLocation().
 * Scheduling is already enabled globally via SchedulerConfig (@EnableScheduling).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MechanicLocationCleanupTask {

    private static final String MECHANIC_GEO_KEY = "mechanics:geo";
    private static final String LASTSEEN_KEY = "mechanics:lastseen";
    private static final long STALE_AFTER_MS = 30_000;

    private final StringRedisTemplate redisTemplate;

    @Scheduled(fixedRate = 30_000)
    public void evictStaleMechanics() {
        long cutoff = System.currentTimeMillis() - STALE_AFTER_MS;
        Set<String> stale = redisTemplate.opsForZSet().rangeByScore(LASTSEEN_KEY, 0, cutoff);
        if (stale != null && !stale.isEmpty()) {
            // GeoOperations.remove(K, M...) is typed to String here — needs a real String[],
            // not the Object[] that Set.toArray() returns with no argument.
            String[] staleIds = stale.toArray(new String[0]);
            redisTemplate.opsForZSet().remove(LASTSEEN_KEY, (Object[]) staleIds);
            redisTemplate.opsForGeo().remove(MECHANIC_GEO_KEY, staleIds);
            log.debug("Evicted {} stale mechanic(s) from Redis geo cache", stale.size());
        }
    }
}
