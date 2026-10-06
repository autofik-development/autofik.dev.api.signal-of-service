package com.autofik.sos.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where a bid placed over the socket waits for its Google Distance Matrix call.
 *
 * <p>The STOMP inbound pool is shared with location pings. A bid blocks on Google for up to 5 s, so
 * a burst of bids filled that pool and every ping queued behind them (load test with a 3 s Google
 * delay: pings p99 5 s, 2 % failed). Running the bid here keeps the inbound thread free.
 *
 * <p>Bounded on purpose: when the queue is full {@link #execute} throws, and the controller answers
 * the client with a 503 error frame it can retry, instead of queueing without limit.
 *
 * <p>It is a plain class, not an {@link Executor} bean: Spring Boot would otherwise treat any
 * Executor bean as the application's task executor.
 */
@Component
public class BidCommandExecutor implements DisposableBean {

  private final ThreadPoolExecutor pool;

  @Autowired
  public BidCommandExecutor(
      @Value("${websocket.bid-commands.threads:32}") int threads,
      @Value("${websocket.bid-commands.queue-capacity:200}") int queueCapacity) {
    AtomicInteger n = new AtomicInteger();
    pool =
        new ThreadPoolExecutor(
            threads,
            threads,
            60,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            r -> {
              Thread t = new Thread(r, "bid-command-" + n.incrementAndGet());
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    pool.allowCoreThreadTimeOut(true);
  }

  /** Runs the command on the calling thread; for unit tests that assert on the reply. */
  public static BidCommandExecutor inline() {
    return new BidCommandExecutor(1, 1) {
      @Override
      public void execute(Runnable command) {
        command.run();
      }
    };
  }

  /**
   * @throws RejectedExecutionException when every thread is busy and the queue is full
   */
  public void execute(Runnable command) {
    pool.execute(command);
  }

  @Override
  public void destroy() {
    pool.shutdownNow();
  }
}
