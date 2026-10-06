package com.autofik.sos.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class AfterCommitTest {

  @AfterEach
  void clear() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void withNoTransaction_runsAtOnce() {
    List<String> ran = new ArrayList<>();

    AfterCommit.run(() -> ran.add("now"));

    assertThat(ran).containsExactly("now");
  }

  @Test
  void inATransaction_waitsForTheCommit_andKeepsOrder() {
    List<String> ran = new ArrayList<>();
    TransactionSynchronizationManager.initSynchronization();

    AfterCommit.run(() -> ran.add("first"));
    AfterCommit.run(() -> ran.add("second"));
    assertThat(ran).isEmpty();

    TransactionSynchronizationUtils.triggerAfterCommit();

    assertThat(ran).containsExactly("first", "second");
  }

  @Test
  void aRollback_neverRuns() {
    List<String> ran = new ArrayList<>();
    TransactionSynchronizationManager.initSynchronization();

    AfterCommit.run(() -> ran.add("x"));
    TransactionSynchronizationUtils.invokeAfterCompletion(
        TransactionSynchronizationManager.getSynchronizations(),
        org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);

    assertThat(ran).isEmpty();
  }

  @Test
  void aFailingAction_doesNotReachTheCaller_orStopTheNext() {
    List<String> ran = new ArrayList<>();
    TransactionSynchronizationManager.initSynchronization();

    AfterCommit.run(
        () -> {
          throw new IllegalStateException("broker down");
        });
    AfterCommit.run(() -> ran.add("next"));
    TransactionSynchronizationUtils.triggerAfterCommit();

    assertThat(ran).containsExactly("next");
  }
}
