package com.autofik.sos.config;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.repository.MechanicRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Point;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * On startup, repopulates the Redis geo cache ("mechanics:geo") from each mechanic's last known
 * Postgres currentLocation.
 * <p>
 * Without this, a freshly started (or restarted) Redis instance has no mechanics in its geo set
 * until each one sends its next GPS ping — meaning findAvailable() could report nobody nearby
 * even though mechanics are actually online and working. This runner closes that gap by seeding
 * Redis from the last durable snapshot in Postgres before the app starts serving traffic.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MechanicLocationCacheWarmup implements ApplicationRunner {

    private static final String MECHANIC_GEO_KEY = "mechanics:geo";

    private final MechanicRepository mechanicRepository;
    private final StringRedisTemplate redisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        List<Mechanic> mechanics = mechanicRepository.findAll();
        int warmed = 0;
        for (Mechanic mechanic : mechanics) {
            Point location = mechanic.getCurrentLocation();
            if (location != null) {
                redisTemplate.opsForGeo().add(
                        MECHANIC_GEO_KEY,
                        new org.springframework.data.geo.Point(location.getX(), location.getY()),
                        mechanic.getMechanicId().toString()
                );
                warmed++;
            }
        }
        log.info("Warmed Redis geo cache with {} mechanic location(s) from Postgres", warmed);
    }
}
