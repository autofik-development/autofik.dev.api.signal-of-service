package com.autofik.sos;

import java.time.ZoneId;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SosApplication {

  static final String DEFAULT_TIMEZONE = "Asia/Phnom_Penh";

  public static void main(String[] args) {
    pinTimeZone(System.getenv("SOS_TIMEZONE"));
    SpringApplication.run(SosApplication.class, args);
  }

  /**
   * Every timestamp here is LocalDateTime.now(): the JVM's wall clock, stored and sent with no
   * zone. Fix that zone so it cannot depend on the host (a container is UTC, a laptop is +07:00),
   * and the stored values, the REST strings, the Kafka events, the sweeps and the logs all agree.
   * Must run before anything reads the clock, so it is the first thing main does.
   */
  static void pinTimeZone(String zoneId) {
    String id = zoneId == null || zoneId.isBlank() ? DEFAULT_TIMEZONE : zoneId.trim();
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(id)));
  }
}
