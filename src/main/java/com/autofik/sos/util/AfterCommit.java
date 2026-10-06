package com.autofik.sos.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a side effect (a Kafka send, a STOMP frame) only once the surrounding database transaction
 * has committed, so a rollback never leaves a consumer or a mobile client with news of a change
 * that did not happen.
 *
 * <p>With no transaction active the action runs at once, which is what the methods that call Google
 * or Kafka outside a {@code TransactionTemplate} want. Several actions registered in one
 * transaction run in the order they were registered.
 *
 * <p>No outbox: an action lost to a broker outage or a crash between commit and send is not
 * replayed.
 */
@Slf4j
public final class AfterCommit {

  private AfterCommit() {}

  public static void run(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            // The commit is done; an exception here would only turn a saved change into a 500.
            try {
              action.run();
            } catch (RuntimeException e) {
              log.error("Publish after commit failed", e);
            }
          }
        });
  }
}
