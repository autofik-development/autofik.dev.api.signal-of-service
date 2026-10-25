package com.autofik.sos.service.impl;

import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Remembers that Redis just failed, so the hot STOMP path stops paying the command timeout on every
 * call.
 *
 * <p>A slow or dead Redis costs the full command timeout (REDIS_TIMEOUT_MS) per call, and one
 * location ping makes up to four calls on a shared inbound thread. After one failure callers skip
 * Redis for a few seconds and take their existing fallback at once; the first call after the window
 * probes Redis again.
 */
@Component
public class RedisHealthGate {

  // ponytail: fixed window, and every thread that arrives after it ends probes at once. Make it a
  // property or add a single-probe latch if that burst ever shows up.
  private static final long DOWN_NANOS = TimeUnit.SECONDS.toNanos(10);

  // System.nanoTime() deadline; compared by subtraction, as the clock may be negative.
  private volatile long downUntil = System.nanoTime();

  public boolean isDown() {
    return System.nanoTime() - downUntil < 0;
  }

  public void markDown() {
    downUntil = System.nanoTime() + DOWN_NANOS;
  }
}
