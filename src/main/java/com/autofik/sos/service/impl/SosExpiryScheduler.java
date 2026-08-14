package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.dto.event.SosStatusUpdatedEvent;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.repository.SosRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Closes the bidding window when nobody bid.
 *
 * <p>Uses {@link TaskScheduler} rather than {@code @Scheduled} because each request has its own
 * expiry instant, and a fixed-rate sweep would leave a driver staring at a dead auction for up to
 * one sweep interval.
 *
 * <p>ponytail: the per-request timers live in this instance's memory, so a crash loses the pending
 * ones. The startup sweep is the recovery path — it expires what fell past its window while the
 * service was down and re-arms the rest. Good enough while restarts are rare; a durable scheduler
 * is the upgrade if they stop being rare.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SosExpiryScheduler {

    private final SosRequestRepository sosRequestRepository;
    private final SosEventProducer sosEventProducer;
    private final SimpMessagingTemplate messagingTemplate;
    private final TaskScheduler taskScheduler;

    /**
     * Catches up on whatever happened while the service was stopped: expire the windows that
     * closed, re-arm timers for the ones still open.
     *
     * <p>Safe to run on every instance. {@link #expire} claims each row with a conditional update,
     * so only one instance can win any given request and the notifications are sent once.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        LocalDateTime now = LocalDateTime.now();

        List<SosRequest> overdue = sosRequestRepository
                .findByStatusAndBiddingExpiresAtBefore(SosStatus.PENDING, now);
        if (!overdue.isEmpty()) {
            log.warn("Expiring {} PENDING SOS request(s) missed while offline", overdue.size());
            overdue.forEach(sos -> expire(sos.getSosId()));
        }

        List<SosRequest> active = sosRequestRepository
                .findByStatusAndBiddingExpiresAtAfter(SosStatus.PENDING, now);
        if (!active.isEmpty()) {
            log.info("Re-arming bidding expiry timer for {} PENDING SOS request(s)", active.size());
            active.forEach(this::schedule);
        }
    }

    /**
     * Arms the timer for one request. The task re-reads by id at trigger time rather than closing
     * over the entity, so it works from whatever state the request is actually in by then.
     */
    public void schedule(SosRequest sos) {
        if (sos.getBiddingExpiresAt() == null) return;
        UUID sosId = sos.getSosId();
        taskScheduler.schedule(
                () -> expire(sosId),
                sos.getBiddingExpiresAt().atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * Expires one request, if it is still there to expire.
     *
     * <p>The claim is a single conditional UPDATE rather than a read followed by a write. A bid
     * accepted in the last second of the window commits between those two statements otherwise, and
     * the timer overwrites an ACCEPTED request with EXPIRED — a driver whose mechanic is already on
     * the way is told nobody came.
     *
     * <p>Notifications are sent only by the caller that won the claim, and only after it has
     * committed: the update runs in the repository's own transaction, so by the time this method
     * reads the row back, the new status is durable.
     */
    public void expire(UUID sosId) {
        int claimed = sosRequestRepository.compareAndSetStatus(sosId, SosStatus.PENDING, SosStatus.EXPIRED);
        if (claimed == 0) {
            log.debug("SOS {} was no longer PENDING at expiry time — nothing to do", sosId);
            return;
        }

        sosRequestRepository.findById(sosId).ifPresent(this::publishExpiry);
    }

    private void publishExpiry(SosRequest sos) {
        UUID sosId = sos.getSosId();
        UUID userId = sos.getUser().getUserId();

        sosEventProducer.publishStatusUpdated(new SosStatusUpdatedEvent(
                sosId, null, userId, SosStatus.PENDING, SosStatus.EXPIRED, LocalDateTime.now()
        ));

        boolean retriesLeft = sos.getRebroadcastCount() < SosRequest.MAX_REBROADCAST;
        String body = retriesLeft
                ? "No mechanic was found. Tap to try again."
                : "No mechanic available after " + SosRequest.MAX_REBROADCAST + " attempts. Please call support.";
        sosEventProducer.publishNotification(new SosNotificationEvent(
                "BIDDING_EXPIRED", userId, "USER", sosId, "Bidding window closed", body
        ));

        messagingTemplate.convertAndSendToUser(
                userId.toString(), "/queue/sos-updates",
                Map.of("type", "BIDDING_EXPIRED", "sosId", sosId,
                        "retriesLeft", retriesLeft,
                        "attemptsLeft", SosRequest.MAX_REBROADCAST - sos.getRebroadcastCount())
        );
    }
}
