package com.autofik.sos.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Defers a side effect until the surrounding transaction has actually resolved.
 *
 * <p>Kafka and WebSocket are not transactional. Publishing them inline means a mechanic can be told
 * about an SOS before the row is visible — their next read 404s — and a rollback leaves everyone
 * notified of a request that never existed. Both are one-way: nothing downstream can be recalled.
 *
 * <p>When no transaction is active the action runs immediately, which is both what a caller outside
 * a transaction means and what keeps the service unit tests reading as they did before.
 *
 * <p>Static rather than a bean, like {@link GeoDistance} and
 * {@link com.autofik.sos.security.Caller}: the underlying
 * {@link TransactionSynchronizationManager} is itself static thread-local state, so there is
 * nothing here to inject or to substitute.
 */
public final class TransactionHooks {

    private TransactionHooks() {
    }

    /** Runs after a successful commit, or immediately when there is no transaction to wait for. */
    public static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * Runs only if the transaction rolls back — for undoing state that lives outside Postgres and
     * therefore does not roll back with it, such as a Redis throttle key. A no-op when there is no
     * transaction, since without one there is nothing that can roll back.
     */
    public static void afterRollback(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    action.run();
                }
            }
        });
    }
}
